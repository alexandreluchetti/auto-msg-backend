package br.com.conversa.app.privacy;

import br.com.conversa.app.tenant.TenantService;
import br.com.conversa.app.tenant.TenantTransactions;
import br.com.conversa.core.tenant.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Retention (ARCHITECTURE section 13): after each business's {@code retention.messageDays}
 * (default 180), messages and queue rows are deleted, and orders are kept with the customer's
 * personal data removed; customers with nothing recent left are anonymized. Unregistered numbers'
 * events go after 30 days. Runs daily, and is safe to run again.
 */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);
    private static final int DEFAULT_DAYS = 180;
    private static final int UNKNOWN_NUMBER_DAYS = 30;

    /** What one run removed or anonymized. */
    public record Result(int messages, int orders, int contacts, int queueRows) {
    }

    private final JdbcClient jdbc;
    private final TenantTransactions tenants;
    private final TenantService tenantService;
    private final TransactionTemplate transactions;

    RetentionJob(JdbcClient jdbc, TenantTransactions tenants, TenantService tenantService,
                 TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.tenantService = tenantService;
        this.transactions = transactions;
    }

    public Result runOnce() {
        int messages = 0;
        int orders = 0;
        int contacts = 0;
        int queueRows = 0;
        List<UUID> all = jdbc.sql("SELECT id FROM tenant").query(UUID.class).list();
        for (UUID id : all) {
            TenantId tenant = new TenantId(id);
            int days = tenantService.effectiveConfig(tenant).path("retention").path("messageDays").asInt(DEFAULT_DAYS);
            Result result = tenants.inTenant(tenant, () -> purgeTenant(tenant, days));
            Integer queue = transactions.execute(status -> purgeQueues(tenant, days));
            messages += result.messages();
            orders += result.orders();
            contacts += result.contacts();
            queueRows += queue == null ? 0 : queue;
        }
        Integer unknown = transactions.execute(status -> jdbc.sql("""
                        DELETE FROM inbound_event
                        WHERE tenant_id IS NULL AND received_at < now() - make_interval(days => :days)
                        """)
                .param("days", UNKNOWN_NUMBER_DAYS).update());
        queueRows += unknown == null ? 0 : unknown;
        Result total = new Result(messages, orders, contacts, queueRows);
        log.info("Retention removed {} messages, cleaned {} orders, anonymized {} contacts, removed {} queue rows",
                total.messages(), total.orders(), total.contacts(), total.queueRows());
        return total;
    }

    private Result purgeTenant(TenantId tenant, int days) {
        int messages = jdbc.sql("""
                        DELETE FROM message WHERE tenant_id = :tenant AND created_at < now() - make_interval(days => :days)
                        """)
                .param("tenant", tenant.value()).param("days", days).update();
        jdbc.sql("""
                        DELETE FROM conversation_summary
                        WHERE tenant_id = :tenant AND updated_at < now() - make_interval(days => :days)
                        """)
                .param("tenant", tenant.value()).param("days", days).update();
        int orders = jdbc.sql("""
                        UPDATE customer_order SET address = NULL, change_for = NULL, updated_at = now()
                        WHERE tenant_id = :tenant AND created_at < now() - make_interval(days => :days)
                          AND (address IS NOT NULL OR change_for IS NOT NULL)
                        """)
                .param("tenant", tenant.value()).param("days", days).update();
        jdbc.sql("""
                        UPDATE order_item SET notes = NULL
                        WHERE tenant_id = :tenant AND notes IS NOT NULL AND order_id IN (
                            SELECT id FROM customer_order
                            WHERE tenant_id = :tenant AND created_at < now() - make_interval(days => :days))
                        """)
                .param("tenant", tenant.value()).param("days", days).update();
        jdbc.sql("""
                        UPDATE checkout SET draft = '{}'
                        WHERE tenant_id = :tenant AND created_at < now() - make_interval(days => :days) AND draft <> '{}'
                        """)
                .param("tenant", tenant.value()).param("days", days).update();
        int contacts = jdbc.sql("""
                        UPDATE contact k SET wa_id = 'erased:' || k.id, display_name = NULL, erased_at = now()
                        WHERE k.tenant_id = :tenant AND k.erased_at IS NULL
                          AND k.created_at < now() - make_interval(days => :days)
                          AND NOT EXISTS (SELECT 1 FROM message m JOIN conversation c ON c.id = m.conversation_id
                                          WHERE c.contact_id = k.id)
                          AND NOT EXISTS (SELECT 1 FROM customer_order o
                                          WHERE o.contact_id = k.id
                                            AND o.created_at >= now() - make_interval(days => :days))
                        """)
                .param("tenant", tenant.value()).param("days", days).update();
        return new Result(messages, orders, contacts, 0);
    }

    private int purgeQueues(TenantId tenant, int days) {
        int inbound = jdbc.sql("""
                        DELETE FROM inbound_event
                        WHERE tenant_id = :tenant AND received_at < now() - make_interval(days => :days)
                        """)
                .param("tenant", tenant.value()).param("days", days).update();
        int outbound = jdbc.sql("""
                        DELETE FROM outbound_message
                        WHERE tenant_id = :tenant AND created_at < now() - make_interval(days => :days)
                          AND status IN ('SENT', 'FAILED')
                        """)
                .param("tenant", tenant.value()).param("days", days).update();
        return inbound + outbound;
    }
}
