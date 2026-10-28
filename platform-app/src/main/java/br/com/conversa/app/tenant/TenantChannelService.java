package br.com.conversa.app.tenant;

import br.com.conversa.core.tenant.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * WhatsApp numbers of each tenant. The {@code phone_number_id} of a webhook event resolves its
 * tenant; the lookups use database functions that work before any tenant context exists.
 */
@Service
public class TenantChannelService {

    private final JdbcClient jdbc;
    private final TenantTransactions tenants;

    public TenantChannelService(JdbcClient jdbc, TenantTransactions tenants) {
        this.jdbc = jdbc;
        this.tenants = tenants;
    }

    /**
     * Adds or updates a number of the tenant.
     *
     * @param accessTokenRef name of the property or environment variable holding this number's
     *                       Cloud API token; null uses the platform token
     * @throws IllegalStateException if the number belongs to another tenant
     */
    public void register(TenantId tenant, String phoneNumberId, String wabaId, String accessTokenRef) {
        Optional<TenantId> owner = tenantOf(phoneNumberId);
        if (owner.isPresent() && !owner.get().equals(tenant)) {
            throw new IllegalStateException("Phone number " + phoneNumberId + " belongs to another tenant");
        }
        tenants.runInTenant(tenant, () -> jdbc.sql("""
                        INSERT INTO tenant_channel (tenant_id, waba_id, phone_number_id, access_token_ref)
                        VALUES (:tenantId, :wabaId, :phoneNumberId, :accessTokenRef)
                        ON CONFLICT (phone_number_id) DO UPDATE
                        SET waba_id = excluded.waba_id, access_token_ref = excluded.access_token_ref,
                            status = 'ACTIVE', updated_at = now()
                        """)
                .param("tenantId", tenant.value())
                .param("wabaId", wabaId)
                .param("phoneNumberId", phoneNumberId)
                .param("accessTokenRef", accessTokenRef)
                .update());
    }

    /** Tenant of an active number, from the edge (no tenant context). */
    public Optional<TenantId> tenantOf(String phoneNumberId) {
        return jdbc.sql("SELECT channel_tenant_id(:phoneNumberId)")
                .param("phoneNumberId", phoneNumberId)
                .query(UUID.class).list().stream()
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .map(TenantId::new);
    }

    /** Access token reference of an active number, or empty to use the platform token. */
    public Optional<String> accessTokenRef(String phoneNumberId) {
        return jdbc.sql("SELECT channel_access_token_ref(:phoneNumberId)")
                .param("phoneNumberId", phoneNumberId)
                .query(String.class).list().stream()
                .filter(ref -> ref != null && !ref.isBlank())
                .findFirst();
    }
}
