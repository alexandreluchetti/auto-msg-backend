package br.com.conversa.app.tenant;

import br.com.conversa.core.segment.SegmentPack;
import br.com.conversa.core.tenant.TenantId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Tenants and their configuration. Creating a tenant is a platform operation (no tenant context
 * yet); reading and changing one runs inside its own tenant context.
 */
@Service
public class TenantService {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,48}[a-z0-9])?$");

    private final JdbcClient jdbc;
    private final TenantTransactions tenants;
    private final SegmentPackRegistry packs;
    private final TenantConfigResolver configs;

    public TenantService(JdbcClient jdbc, TenantTransactions tenants, SegmentPackRegistry packs,
                         TenantConfigResolver configs) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.packs = packs;
        this.configs = configs;
    }

    /**
     * @param configJson the tenant's own settings; pack defaults fill the rest
     * @throws InvalidTenantConfigException if the configuration does not satisfy the pack's schema
     */
    public TenantId create(String slug, String displayName, String segmentId, String configJson) {
        if (slug == null || !SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException("Slug must be lowercase letters, digits and hyphens: " + slug);
        }
        SegmentPack pack = packs.latest(segmentId);
        configs.effectiveConfig(pack, configJson);
        try {
            UUID id = jdbc.sql("""
                            INSERT INTO tenant (slug, display_name, segment_id, segment_version, config)
                            VALUES (:slug, :displayName, :segmentId, :segmentVersion, CAST(:config AS jsonb))
                            RETURNING id
                            """)
                    .param("slug", slug)
                    .param("displayName", displayName)
                    .param("segmentId", pack.id())
                    .param("segmentVersion", pack.version())
                    .param("config", configJson == null || configJson.isBlank() ? "{}" : configJson)
                    .query(UUID.class).single();
            return new TenantId(id);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("Tenant slug already exists: " + slug, e);
        }
    }

    /** Platform lookup (no tenant context), used by the bootstrap. */
    public Optional<TenantId> findBySlug(String slug) {
        return jdbc.sql("SELECT id FROM tenant WHERE slug = :slug")
                .param("slug", slug)
                .query(UUID.class).optional()
                .map(TenantId::new);
    }

    /**
     * Replaces the tenant's own settings.
     *
     * @throws InvalidTenantConfigException if the result does not satisfy the pack's schema; nothing changes
     */
    public void updateConfig(TenantId tenant, String configJson) {
        tenants.runInTenant(tenant, () -> {
            SegmentPack pack = packOf(tenant);
            configs.effectiveConfig(pack, configJson);
            jdbc.sql("UPDATE tenant SET config = CAST(:config AS jsonb), updated_at = now() WHERE id = :id")
                    .param("config", configJson)
                    .param("id", tenant.value())
                    .update();
        });
    }

    /** Pack defaults merged with the tenant's settings. */
    public JsonNode effectiveConfig(TenantId tenant) {
        return tenants.inTenant(tenant, () -> {
            String own = jdbc.sql("SELECT config::text FROM tenant WHERE id = :id")
                    .param("id", tenant.value())
                    .query(String.class).single();
            return configs.effectiveConfig(packOf(tenant), own);
        });
    }

    private SegmentPack packOf(TenantId tenant) {
        record Segment(String segmentId, int segmentVersion) {
        }
        Segment segment = jdbc.sql("SELECT segment_id, segment_version FROM tenant WHERE id = :id")
                .param("id", tenant.value())
                .query(Segment.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("Unknown tenant: " + tenant));
        return packs.require(segment.segmentId(), segment.segmentVersion());
    }
}
