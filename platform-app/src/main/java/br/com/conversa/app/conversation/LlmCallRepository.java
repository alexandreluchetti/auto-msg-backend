package br.com.conversa.app.conversation;

import br.com.conversa.app.ops.OperationsMetrics;
import br.com.conversa.app.tenant.TenantTransactions;
import br.com.conversa.core.llm.LlmResponse;
import br.com.conversa.core.tenant.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

/**
 * Cost of every model call per tenant (ADR-005), written as soon as the call returns.
 */
@Repository
class LlmCallRepository implements AssistantEngine.CallLog {

    private static final Logger log = LoggerFactory.getLogger(LlmCallRepository.class);

    private final JdbcClient jdbc;
    private final TenantTransactions tenants;
    private final LlmPricing pricing;
    private final OperationsMetrics metrics;

    LlmCallRepository(JdbcClient jdbc, TenantTransactions tenants, LlmPricing pricing, OperationsMetrics metrics) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.pricing = pricing;
        this.metrics = metrics;
    }

    @Override
    public void record(TenantId tenant, UUID conversation, String requestedModel, String effort, LlmResponse response,
                       Duration duration) {
        if (!pricing.knows(response.model())) {
            log.warn("No price for model {}; its calls are recorded at zero cost", response.model());
        }
        BigDecimal cost = pricing.cost(response.model(), response.usage());
        tenants.runInTenant(tenant, () -> jdbc.sql("""
                        INSERT INTO llm_call (tenant_id, conversation_id, requested_model, model, effort, stop_reason,
                                              input_tokens, output_tokens, cache_read_tokens, cache_write_tokens,
                                              cost_usd, duration_ms)
                        VALUES (:tenant, :conversation, :requestedModel, :model, :effort, :stopReason,
                                :input, :output, :cacheRead, :cacheWrite, :cost, :duration)
                        """)
                .param("tenant", tenant.value())
                .param("conversation", conversation)
                .param("requestedModel", requestedModel)
                .param("model", response.model())
                .param("effort", effort)
                .param("stopReason", response.stopReason().name())
                .param("input", response.usage().inputTokens())
                .param("output", response.usage().outputTokens())
                .param("cacheRead", response.usage().cacheReadTokens())
                .param("cacheWrite", response.usage().cacheWriteTokens())
                .param("cost", cost)
                .param("duration", duration.toMillis())
                .update());
        metrics.llmCall(tenant, response.model(), cost, response.usage().inputTokens(), response.usage().outputTokens(),
                response.usage().cacheReadTokens(), response.usage().cacheWriteTokens());
    }
}
