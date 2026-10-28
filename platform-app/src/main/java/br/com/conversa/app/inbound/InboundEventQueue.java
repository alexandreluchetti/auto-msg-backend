package br.com.conversa.app.inbound;

import br.com.conversa.channel.whatsapp.webhook.WebhookEventType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/**
 * Consumer side of {@code inbound_event}. {@link #claimNext()} must run inside the transaction that
 * processes the event: the row lock keeps other workers away until it commits.
 */
@Repository
class InboundEventQueue {

    private final JdbcClient jdbc;

    InboundEventQueue(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<ClaimedInbound> claimNext() {
        return jdbc.sql("""
                        SELECT id, event_key, event_type, phone_number_id, wa_id, payload::text AS payload, attempts
                        FROM inbound_event
                        WHERE status = 'PENDING' AND available_at <= now()
                        ORDER BY available_at, id
                        LIMIT 1
                        FOR UPDATE SKIP LOCKED
                        """)
                .query((rs, row) -> new ClaimedInbound(
                        rs.getLong("id"),
                        rs.getString("event_key"),
                        WebhookEventType.valueOf(rs.getString("event_type")),
                        rs.getString("phone_number_id"),
                        rs.getString("wa_id"),
                        rs.getString("payload"),
                        rs.getInt("attempts")))
                .optional();
    }

    void markProcessed(long id) {
        jdbc.sql("""
                        UPDATE inbound_event SET status = 'PROCESSED', processed_at = now(), last_error = NULL
                        WHERE id = :id
                        """)
                .param("id", id).update();
    }

    /** Called in a new transaction after processing failed and was rolled back. */
    void recordFailure(long id, int maxAttempts, Duration delay, String error) {
        jdbc.sql("""
                        UPDATE inbound_event
                        SET attempts = attempts + 1,
                            status = CASE WHEN attempts + 1 >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
                            available_at = now() + make_interval(secs => :delaySeconds),
                            last_error = :error
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("maxAttempts", maxAttempts)
                .param("delaySeconds", delay.toMillis() / 1000.0)
                .param("error", error)
                .update();
    }

    record ClaimedInbound(long id, String eventKey, WebhookEventType type, String phoneNumberId, String waId,
                          String payloadJson, int attempts) {
    }
}
