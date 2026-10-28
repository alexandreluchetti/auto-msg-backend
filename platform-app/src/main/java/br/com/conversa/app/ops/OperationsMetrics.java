package br.com.conversa.app.ops;

import br.com.conversa.core.tenant.TenantId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Metrics of the business side (ARCHITECTURE section 14), exported at {@code /actuator/prometheus}:
 * LLM cost and tokens, billable WhatsApp messages, handoffs and orders per tenant (tagged with its
 * slug), and the queues' backlog. The Grafana dashboard in {@code deploy/grafana} reads them.
 */
@Component
public class OperationsMetrics {

    private final MeterRegistry registry;
    private final JdbcClient jdbc;
    private final Map<TenantId, String> slugs = new ConcurrentHashMap<>();

    OperationsMetrics(MeterRegistry registry, JdbcClient jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;
        Gauge.builder("conversa.queue.inbound.lag", this, metrics -> metrics.number("""
                        SELECT coalesce(extract(epoch FROM now() - min(available_at)), 0)
                        FROM inbound_event WHERE status = 'PENDING' AND available_at <= now()
                        """))
                .description("Age of the oldest inbound event waiting to be processed")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder("conversa.queue.outbound.pending", this, metrics -> metrics.number("""
                        SELECT count(*) FROM outbound_message WHERE status IN ('PENDING', 'PROCESSING')
                        """))
                .description("Outbound messages not sent yet")
                .register(registry);
        Gauge.builder("conversa.conversations.human", this, metrics -> metrics.number("""
                        SELECT count(*) FROM conversation WHERE state = 'HUMAN' AND paused_until > now()
                        """))
                .description("Conversations staff are handling now")
                .register(registry);
    }

    public void llmCall(TenantId tenant, String model, BigDecimal costUsd, long inputTokens, long outputTokens,
                        long cacheReadTokens, long cacheWriteTokens) {
        String slug = slug(tenant);
        Counter.builder("conversa.llm.cost").baseUnit("usd").tag("tenant", slug).tag("model", model)
                .register(registry).increment(costUsd.doubleValue());
        tokens(slug, model, "input", inputTokens);
        tokens(slug, model, "output", outputTokens);
        tokens(slug, model, "cache_read", cacheReadTokens);
        tokens(slug, model, "cache_write", cacheWriteTokens);
    }

    /** A message Meta reported as delivered and billable. */
    public void billableMessage(TenantId tenant, String category) {
        Counter.builder("conversa.whatsapp.billable.messages").tag("tenant", slug(tenant))
                .tag("category", category == null ? "unknown" : category)
                .register(registry).increment();
    }

    public void handoff(TenantId tenant, String reason) {
        Counter.builder("conversa.handoffs").tag("tenant", slug(tenant)).tag("reason", reason)
                .register(registry).increment();
    }

    public void orderCreated(TenantId tenant, BigDecimal total) {
        String slug = slug(tenant);
        Counter.builder("conversa.orders").tag("tenant", slug).register(registry).increment();
        Counter.builder("conversa.orders.value").baseUnit("brl").tag("tenant", slug).register(registry)
                .increment(total.doubleValue());
    }

    private void tokens(String slug, String model, String type, long count) {
        if (count > 0) {
            Counter.builder("conversa.llm.tokens").tag("tenant", slug).tag("model", model).tag("type", type)
                    .register(registry).increment(count);
        }
    }

    /** Slug of the tenant (owner-role lookup, cached): readable tags for the dashboards. */
    private String slug(TenantId tenant) {
        return slugs.computeIfAbsent(tenant, id -> jdbc.sql("SELECT slug FROM tenant WHERE id = :id")
                .param("id", id.value()).query(String.class).optional().orElse(id.toString()));
    }

    private double number(String sql) {
        Number value = jdbc.sql(sql).query(Number.class).single();
        return value == null ? 0 : value.doubleValue();
    }
}
