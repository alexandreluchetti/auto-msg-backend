package br.com.conversa.app.api;

import br.com.conversa.core.tenant.TenantId;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.io.Serial;
import java.util.List;
import java.util.UUID;

/**
 * A signed-in staff member. Every panel request works inside this user's tenant.
 */
public class StaffUser extends User {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID id;
    private final TenantId tenant;
    private final String displayName;

    StaffUser(UUID id, TenantId tenant, String email, String displayName, String passwordHash) {
        super(email, passwordHash, List.of(new SimpleGrantedAuthority("ROLE_STAFF")));
        this.id = id;
        this.tenant = tenant;
        this.displayName = displayName;
    }

    public UUID id() {
        return id;
    }

    public TenantId tenant() {
        return tenant;
    }

    public String displayName() {
        return displayName;
    }
}
