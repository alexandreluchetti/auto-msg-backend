package br.com.conversa.app.api;

import br.com.conversa.app.tenant.TenantTransactions;
import br.com.conversa.core.tenant.TenantId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Staff users of the panel (D-010). Sign-in looks them up by e-mail through the
 * {@code staff_login} function, before any tenant is known; everything else runs in the tenant.
 */
@Service
public class StaffDirectory implements UserDetailsService {

    private static final int MIN_PASSWORD = 10;

    private final JdbcClient jdbc;
    private final TenantTransactions tenants;
    private final PasswordEncoder passwords;

    StaffDirectory(JdbcClient jdbc, TenantTransactions tenants, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.passwords = passwords;
    }

    @Override
    public StaffUser loadUserByUsername(String email) {
        record Row(UUID id, UUID tenantId, String name, String passwordHash) {
        }
        Row row = jdbc.sql("SELECT id, tenant_id, name, password_hash FROM staff_login(:email)")
                .param("email", email)
                .query(Row.class).optional()
                .orElseThrow(() -> new UsernameNotFoundException("No active staff user"));
        return new StaffUser(row.id(), new TenantId(row.tenantId()), email, row.name(), row.passwordHash());
    }

    /**
     * Creates the staff user, or sets the name and password of the existing one with that e-mail in
     * the same tenant.
     *
     * @throws IllegalArgumentException for a short password or an e-mail used by another tenant
     */
    public void upsert(TenantId tenant, String email, String name, String password) {
        if (password == null || password.length() < MIN_PASSWORD) {
            throw new IllegalArgumentException("Staff passwords need at least " + MIN_PASSWORD + " characters");
        }
        String hash = passwords.encode(password);
        try {
            tenants.runInTenant(tenant, () -> {
                int updated = jdbc.sql("""
                                UPDATE staff_user SET name = :name, password_hash = :hash, active = true, updated_at = now()
                                WHERE lower(email) = lower(:email)
                                """)
                        .param("name", name).param("hash", hash).param("email", email)
                        .update();
                if (updated == 0) {
                    jdbc.sql("""
                                    INSERT INTO staff_user (tenant_id, email, name, password_hash)
                                    VALUES (:tenant, :email, :name, :hash)
                                    """)
                            .param("tenant", tenant.value()).param("email", email).param("name", name).param("hash", hash)
                            .update();
                }
            });
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("This e-mail belongs to a staff user of another business", e);
        }
    }
}
