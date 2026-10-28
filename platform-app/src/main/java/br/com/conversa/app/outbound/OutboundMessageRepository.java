package br.com.conversa.app.outbound;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

/**
 * Outbox of messages to send (ADR-004). Rows are claimed with a lease instead of a long
 * transaction, so no database connection is held during the HTTP call; a row whose lease expires
 * (sender crashed) is claimed again. Delivery is therefore at least once.
 */
@Repository
public class OutboundMessageRepository {

    private static final String ENQUEUE = """
            INSERT INTO outbound_message (idempotency_key, phone_number_id, wa_id, kind, payload)
            VALUES (:key, :phoneNumberId, :waId, :kind, CAST(:payload AS jsonb))
            ON CONFLICT (idempotency_key) DO NOTHING
            """;

    private static final String CLAIM = """
            UPDATE outbound_message
            SET status = 'PROCESSING', attempts = attempts + 1,
                available_at = now() + make_interval(secs => :leaseSeconds)
            WHERE id IN (SELECT id FROM outbound_message
                         WHERE status IN ('PENDING', 'PROCESSING') AND available_at <= now()
                         ORDER BY available_at, id
                         LIMIT :limit
                         FOR UPDATE SKIP LOCKED)
            RETURNING id, kind, phone_number_id, wa_id, payload::text AS payload, attempts
            """;

    /** Statuses only move forward, because status webhooks may arrive out of order. */
    private static final String DELIVERY_STATUS = """
            UPDATE outbound_message
            SET delivery_status = :status,
                pricing_category = coalesce(:category, pricing_category),
                billable = coalesce(:billable, billable)
            WHERE wamid = :wamid
              AND coalesce(array_position(ARRAY['sent', 'delivered', 'read', 'failed'], delivery_status), 0)
                  < array_position(ARRAY['sent', 'delivered', 'read', 'failed'], CAST(:status AS text))
            """;

    private final JdbcClient jdbc;

    public OutboundMessageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return false if a request with the same key was already enqueued */
    public boolean enqueue(String idempotencyKey, OutboundRequest request) {
        return jdbc.sql(ENQUEUE)
                .param("key", idempotencyKey)
                .param("phoneNumberId", request.phoneNumberId())
                .param("waId", request.waId())
                .param("kind", request.kind().name())
                .param("payload", request.payloadJson())
                .update() == 1;
    }

    List<ClaimedOutbound> claimDue(int limit, Duration lease) {
        return jdbc.sql(CLAIM)
                .param("leaseSeconds", seconds(lease))
                .param("limit", limit)
                .query((rs, row) -> new ClaimedOutbound(
                        rs.getLong("id"),
                        new OutboundRequest(OutboundKind.valueOf(rs.getString("kind")),
                                rs.getString("phone_number_id"), rs.getString("wa_id"), rs.getString("payload")),
                        rs.getInt("attempts")))
                .list().stream()
                .sorted(Comparator.comparingLong(ClaimedOutbound::id))
                .toList();
    }

    void markSent(long id, String wamid) {
        jdbc.sql("""
                        UPDATE outbound_message
                        SET status = 'SENT', sent_at = now(), wamid = :wamid, last_error = NULL
                        WHERE id = :id
                        """)
                .param("id", id).param("wamid", wamid).update();
    }

    void reschedule(long id, Duration delay, String error) {
        jdbc.sql("""
                        UPDATE outbound_message
                        SET status = 'PENDING', available_at = now() + make_interval(secs => :delaySeconds),
                            last_error = :error
                        WHERE id = :id
                        """)
                .param("id", id).param("delaySeconds", seconds(delay)).param("error", error).update();
    }

    void markFailed(long id, String error) {
        jdbc.sql("UPDATE outbound_message SET status = 'FAILED', last_error = :error WHERE id = :id")
                .param("id", id).param("error", error).update();
    }

    /** Records a status webhook ({@code sent}, {@code delivered}, {@code read}, {@code failed}). */
    public void updateDeliveryStatus(String wamid, String status, String pricingCategory, Boolean billable) {
        jdbc.sql(DELIVERY_STATUS)
                .param("wamid", wamid)
                .param("status", status)
                .param("category", pricingCategory)
                .param("billable", billable)
                .update();
    }

    private static double seconds(Duration duration) {
        return duration.toMillis() / 1000.0;
    }

    record ClaimedOutbound(long id, OutboundRequest request, int attempts) {
    }
}
