package br.com.conversa.app.api;

import br.com.conversa.core.tenant.TenantId;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.io.Serial;
import java.util.List;
import java.util.UUID;

/**
 * A signed-in staff member. Every panel request works inside this user's tenant. Admins also edit
 * the assistant's persona and settings and use the simulator (H8).
 */
public class StaffUser extends User {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String ADMIN = "ADMIN";

    private final UUID id;
    private final TenantId tenant;
    private final String displayName;
    private final String role;

    StaffUser(UUID id, TenantId tenant, String email, String displayName, String passwordHash, String role) {
        super(email, passwordHash, ADMIN.equals(role)
                ? List.of(new SimpleGrantedAuthority("ROLE_STAFF"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                : List.of(new SimpleGrantedAuthority("ROLE_STAFF")));
        this.id = id;
        this.tenant = tenant;
        this.displayName = displayName;
        this.role = role;
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

    /** {@code ADMIN} or {@code STAFF}. */
    public String role() {
        return role;
    }
}
