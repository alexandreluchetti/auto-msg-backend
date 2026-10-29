package br.com.conversa.app.compose;

import br.com.conversa.core.tenant.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

/**
 * A customer who writes again while the bot is still "typing" (ADR-011, step H4): the messages of
 * that turn not sent yet are cancelled, and the bot's stored answer keeps only what was sent (or
 * goes away), so the next answer is rebuilt with everything the customer said. Must run inside the
 * tenant's transaction.
 */
@Component
public class TurnCancellation {

    private static final Logger log = LoggerFactory.getLogger(TurnCancellation.class);

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    TurnCancellation(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** @return turns that had pending messages */
    public int cancelPending(TenantId tenant, UUID conversation, String phoneNumberId, String waId) {
        List<UUID> turns = jdbc.sql("""
                        UPDATE outbound_message SET status = 'CANCELLED', last_error = 'customer wrote again'
                        WHERE tenant_id = :tenant AND phone_number_id = :phone AND wa_id = :waId
                          AND turn_id IS NOT NULL AND status = 'PENDING'
                        RETURNING turn_id
                        """)
                .param("tenant", tenant.value()).param("phone", phoneNumberId).param("waId", waId)
                .query(UUID.class).list().stream().distinct().toList();
        for (UUID turn : turns) {
            record Row(String idempotencyKey, String status, String payload) {
            }
            List<Row> bubbles = jdbc.sql("""
                            SELECT idempotency_key, status, payload::text AS payload FROM outbound_message
                            WHERE turn_id = :turn AND kind IN ('TEXT', 'BUTTONS', 'LIST')
                            ORDER BY turn_seq
                            """)
                    .param("turn", turn).query(Row.class).list();
            if (bubbles.isEmpty()) {
                continue;
            }
            String ref = bubbles.getFirst().idempotencyKey();
            List<String> sent = bubbles.stream().filter(row -> !"CANCELLED".equals(row.status()))
                    .map(row -> mapper.readTree(row.payload()).path("body").asString()).toList();
            if (sent.isEmpty()) {
                jdbc.sql("DELETE FROM message WHERE conversation_id = :conversation AND ref = :ref")
                        .param("conversation", conversation).param("ref", ref).update();
            } else {
                jdbc.sql("UPDATE message SET text = :text WHERE conversation_id = :conversation AND ref = :ref")
                        .param("text", String.join("\n\n", sent)).param("conversation", conversation).param("ref", ref)
                        .update();
            }
        }
        if (!turns.isEmpty()) {
            log.info("Conversation {}: the customer wrote again, {} pending answer(s) cancelled", conversation,
                    turns.size());
        }
        return turns.size();
    }
}
