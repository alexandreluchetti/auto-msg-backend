package br.com.conversa.app.tenant;

import br.com.conversa.core.segment.SegmentPack;
import br.com.conversa.core.tenant.TenantId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;
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
            jdbc.sql("""
                            INSERT INTO tenant_config_version (tenant_id, version, config, status, published_at)
                            SELECT id, 1, config, 'PUBLISHED', now() FROM tenant WHERE id = :id
                            """)
                    .param("id", id).update();
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
     * Replaces the tenant's own settings and publishes them as a new version at once (bootstrap,
     * platform operations; the panel goes through {@link #saveDraft} and {@link #publishDraft}).
     *
     * @throws InvalidTenantConfigException if the result does not satisfy the pack's schema; nothing changes
     */
    public void updateConfig(TenantId tenant, String configJson) {
        tenants.runInTenant(tenant, () -> {
            configs.effectiveConfig(packInContext(tenant), configJson);
            publishVersion(tenant, configJson, null);
        });
    }

    /** A version of the tenant's own settings. */
    public record ConfigVersion(int version, String status, OffsetDateTime createdAt, OffsetDateTime publishedAt) {
    }

    /**
     * Saves the tenant's own settings as the draft (one per tenant), which the simulator runs.
     *
     * @throws InvalidTenantConfigException if the result does not satisfy the pack's schema
     */
    public int saveDraft(TenantId tenant, String configJson, UUID staffId) {
        return tenants.inTenant(tenant, () -> {
            configs.effectiveConfig(packInContext(tenant), configJson);
            int updated = jdbc.sql("""
                            UPDATE tenant_config_version SET config = CAST(:config AS jsonb), created_by = :staff,
                                                             created_at = now()
                            WHERE tenant_id = :tenant AND status = 'DRAFT'
                            """)
                    .param("config", configJson).param("staff", staffId, Types.OTHER).param("tenant", tenant.value())
                    .update();
            if (updated == 0) {
                jdbc.sql("""
                                INSERT INTO tenant_config_version (tenant_id, version, config, status, created_by)
                                VALUES (:tenant, :version, CAST(:config AS jsonb), 'DRAFT', :staff)
                                """)
                        .param("tenant", tenant.value()).param("version", nextVersion(tenant))
                        .param("config", configJson).param("staff", staffId, Types.OTHER).update();
            }
            return jdbc.sql("SELECT version FROM tenant_config_version WHERE tenant_id = :t AND status = 'DRAFT'")
                    .param("t", tenant.value()).query(Integer.class).single();
        });
    }

    /**
     * Makes the draft the live configuration (what WhatsApp uses).
     *
     * @return the published version, or empty when there is no draft
     */
    public Optional<Integer> publishDraft(TenantId tenant, UUID staffId) {
        return tenants.inTenant(tenant, () -> draftConfigInContext(tenant).map(draft -> {
            configs.effectiveConfig(packInContext(tenant), draft);
            jdbc.sql("UPDATE tenant_config_version SET status = 'ARCHIVED' WHERE tenant_id = :t AND status = 'PUBLISHED'")
                    .param("t", tenant.value()).update();
            int version = jdbc.sql("""
                            UPDATE tenant_config_version
                            SET status = 'PUBLISHED', published_at = now(), created_by = coalesce(:staff, created_by)
                            WHERE tenant_id = :t AND status = 'DRAFT'
                            RETURNING version
                            """)
                    .param("staff", staffId, Types.OTHER).param("t", tenant.value()).query(Integer.class).single();
            jdbc.sql("UPDATE tenant SET config = CAST(:config AS jsonb), updated_at = now() WHERE id = :id")
                    .param("config", draft).param("id", tenant.value()).update();
            return version;
        }));
    }

    /** The tenant's own draft settings, if any. */
    public Optional<String> draftConfig(TenantId tenant) {
        return tenants.inTenant(tenant, () -> draftConfigInContext(tenant));
    }

    /** The tenant's own published settings. */
    public String publishedConfig(TenantId tenant) {
        return tenants.inTenant(tenant, () -> jdbc.sql("SELECT config::text FROM tenant WHERE id = :id")
                .param("id", tenant.value()).query(String.class).single());
    }

    /** Pack defaults merged with the draft (or, without a draft, the published settings). */
    public JsonNode effectiveDraftConfig(TenantId tenant) {
        return tenants.inTenant(tenant, () -> configs.effectiveConfig(packInContext(tenant),
                draftConfigInContext(tenant).orElseGet(() -> publishedConfig(tenant))));
    }

    public List<ConfigVersion> versions(TenantId tenant) {
        return tenants.inTenant(tenant, () -> jdbc.sql("""
                        SELECT version, status, created_at, published_at FROM tenant_config_version
                        WHERE tenant_id = :t ORDER BY version DESC
                        """)
                .param("t", tenant.value()).query(ConfigVersion.class).list());
    }

    private Optional<String> draftConfigInContext(TenantId tenant) {
        return jdbc.sql("SELECT config::text FROM tenant_config_version WHERE tenant_id = :t AND status = 'DRAFT'")
                .param("t", tenant.value()).query(String.class).optional();
    }

    private int publishVersion(TenantId tenant, String configJson, UUID staffId) {
        int version = nextVersion(tenant);
        jdbc.sql("UPDATE tenant_config_version SET status = 'ARCHIVED' WHERE tenant_id = :t AND status = 'PUBLISHED'")
                .param("t", tenant.value()).update();
        jdbc.sql("""
                        INSERT INTO tenant_config_version (tenant_id, version, config, status, created_by, published_at)
                        VALUES (:t, :version, CAST(:config AS jsonb), 'PUBLISHED', :staff, now())
                        """)
                .param("t", tenant.value()).param("version", version).param("config", configJson)
                .param("staff", staffId, Types.OTHER).update();
        jdbc.sql("UPDATE tenant SET config = CAST(:config AS jsonb), updated_at = now() WHERE id = :id")
                .param("config", configJson).param("id", tenant.value()).update();
        return version;
    }

    private int nextVersion(TenantId tenant) {
        return jdbc.sql("SELECT coalesce(max(version), 0) + 1 FROM tenant_config_version WHERE tenant_id = :t")
                .param("t", tenant.value()).query(Integer.class).single();
    }

    /** Pack defaults merged with the tenant's settings. */
    public JsonNode effectiveConfig(TenantId tenant) {
        return tenants.inTenant(tenant, () -> {
            String own = jdbc.sql("SELECT config::text FROM tenant WHERE id = :id")
                    .param("id", tenant.value())
                    .query(String.class).single();
            return configs.effectiveConfig(packInContext(tenant), own);
        });
    }

    /** Segment Pack (and version) the tenant runs on. */
    public SegmentPack packOf(TenantId tenant) {
        return tenants.inTenant(tenant, () -> packInContext(tenant));
    }

    private SegmentPack packInContext(TenantId tenant) {
        record Segment(String segmentId, int segmentVersion) {
        }
        Segment segment = jdbc.sql("SELECT segment_id, segment_version FROM tenant WHERE id = :id")
                .param("id", tenant.value())
                .query(Segment.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("Unknown tenant: " + tenant));
        return packs.require(segment.segmentId(), segment.segmentVersion());
    }
}
