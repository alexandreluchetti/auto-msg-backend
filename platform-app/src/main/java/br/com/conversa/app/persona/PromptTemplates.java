package br.com.conversa.app.persona;

import br.com.conversa.app.tenant.TenantTransactions;
import br.com.conversa.core.segment.SegmentPack;
import br.com.conversa.core.tenant.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Types;
import java.util.List;
import java.util.Optional;

/**
 * Versioned prompt templates (ADR-011): each segment has a default, and a tenant may have its own
 * override; each has at most one draft and one published version, older ones archived. The pack's
 * {@code prompts/system.md} is seeded at startup as a new published default whenever it changes, so
 * the history of what the assistant was told stays in the database.
 */
@Service
public class PromptTemplates implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplates.class);

    public enum Status { DRAFT, PUBLISHED }

    private final JdbcClient jdbc;
    private final TenantTransactions tenants;
    private final TransactionTemplate transactions;
    private final List<SegmentPack> packs;

    PromptTemplates(JdbcClient jdbc, TenantTransactions tenants, TransactionTemplate transactions,
                    List<SegmentPack> packs) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.transactions = transactions;
        this.packs = packs;
    }

    @Override
    public void run(ApplicationArguments args) {
        packs.forEach(this::seed);
    }

    /** Publishes the pack's template as the segment default if it differs from the published one. */
    void seed(SegmentPack pack) {
        transactions.executeWithoutResult(status -> {
            String shipped = pack.defaultPromptTemplate();
            Optional<String> published = jdbc.sql("""
                            SELECT body FROM prompt_template
                            WHERE segment_id = :segment AND tenant_id IS NULL AND status = 'PUBLISHED'
                            """)
                    .param("segment", pack.id()).query(String.class).optional();
            if (published.isPresent() && published.get().equals(shipped)) {
                return;
            }
            int version = publish(pack.id(), null, shipped, "shipped with the " + pack.id() + " pack");
            log.info("Published version {} of the {} prompt template", version, pack.id());
        });
    }

    /**
     * The template the tenant's assistant uses: its own published override, else the segment's
     * published default, else the pack's file.
     *
     * @param status {@code DRAFT} prefers the tenant's draft override (simulator), then the published one
     */
    public String template(TenantId tenant, SegmentPack pack, Status status) {
        return tenants.inTenant(tenant, () -> {
            if (status == Status.DRAFT) {
                Optional<String> draft = find(pack.id(), tenant, "DRAFT");
                if (draft.isPresent()) {
                    return draft.get();
                }
            }
            return find(pack.id(), tenant, "PUBLISHED")
                    .or(() -> jdbc.sql("""
                                    SELECT body FROM prompt_template
                                    WHERE segment_id = :segment AND tenant_id IS NULL AND status = 'PUBLISHED'
                                    """)
                            .param("segment", pack.id()).query(String.class).optional())
                    .orElseGet(pack::defaultPromptTemplate);
        });
    }

    /** Saves (or replaces) the tenant's draft override. */
    public void saveTenantDraft(TenantId tenant, String segment, String body) {
        tenants.runInTenant(tenant, () -> {
            int updated = jdbc.sql("""
                            UPDATE prompt_template SET body = :body, created_at = now()
                            WHERE segment_id = :segment AND tenant_id = :tenant AND status = 'DRAFT'
                            """)
                    .param("body", body).param("segment", segment).param("tenant", tenant.value()).update();
            if (updated == 0) {
                jdbc.sql("""
                                INSERT INTO prompt_template (segment_id, tenant_id, version, body, status)
                                VALUES (:segment, :tenant, :version, :body, 'DRAFT')
                                """)
                        .param("segment", segment).param("tenant", tenant.value())
                        .param("version", nextVersion(segment, tenant)).param("body", body).update();
            }
        });
    }

    /** Publishes the tenant's draft override. @return false when there is no draft */
    public boolean publishTenantDraft(TenantId tenant, String segment) {
        return tenants.inTenant(tenant, () -> {
            Optional<String> draft = find(segment, tenant, "DRAFT");
            draft.ifPresent(body -> {
                jdbc.sql("DELETE FROM prompt_template WHERE segment_id = :segment AND tenant_id = :tenant AND status = 'DRAFT'")
                        .param("segment", segment).param("tenant", tenant.value()).update();
                publish(segment, tenant, body, null);
            });
            return draft.isPresent();
        });
    }

    private int publish(String segment, TenantId tenant, String body, String note) {
        int version = nextVersion(segment, tenant);
        jdbc.sql("""
                        UPDATE prompt_template SET status = 'ARCHIVED'
                        WHERE segment_id = :segment AND tenant_id IS NOT DISTINCT FROM :tenant AND status = 'PUBLISHED'
                        """)
                .param("segment", segment).param("tenant", tenant == null ? null : tenant.value(), Types.OTHER).update();
        jdbc.sql("""
                        INSERT INTO prompt_template (segment_id, tenant_id, version, body, status, note, published_at)
                        VALUES (:segment, :tenant, :version, :body, 'PUBLISHED', :note, now())
                        """)
                .param("segment", segment).param("tenant", tenant == null ? null : tenant.value(), Types.OTHER)
                .param("version", version).param("body", body).param("note", note).update();
        return version;
    }

    private int nextVersion(String segment, TenantId tenant) {
        return jdbc.sql("""
                        SELECT coalesce(max(version), 0) + 1 FROM prompt_template
                        WHERE segment_id = :segment AND tenant_id IS NOT DISTINCT FROM :tenant
                        """)
                .param("segment", segment).param("tenant", tenant == null ? null : tenant.value(), Types.OTHER)
                .query(Integer.class).single();
    }

    private Optional<String> find(String segment, TenantId tenant, String status) {
        return jdbc.sql("""
                        SELECT body FROM prompt_template
                        WHERE segment_id = :segment AND tenant_id = :tenant AND status = :status
                        """)
                .param("segment", segment).param("tenant", tenant.value()).param("status", status)
                .query(String.class).optional();
    }
}
