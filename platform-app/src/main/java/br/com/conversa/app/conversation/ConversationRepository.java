package br.com.conversa.app.conversation;

import br.com.conversa.core.tenant.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Contacts, conversations and their messages. Everything except {@link #claimDue} runs inside the
 * caller's tenant context (RLS); claiming is cross-tenant work of the reply worker (ADR-010).
 */
@Repository
public class ConversationRepository {

    /** A stored message as the engine reads it back. */
    public record StoredMessage(String direction, String author, String wamid, String text, Instant sentAt) {

        public boolean fromCustomer() {
            return "IN".equals(direction);
        }
    }

    public record ConversationView(UUID id, String phoneNumberId, String waId, String state) {
    }

    /** A conversation reserved for one answer; {@code dueAt} is the lease the claim wrote. */
    public record ClaimedReply(UUID conversationId, TenantId tenant, OffsetDateTime dueAt, int attempts) {
    }

    private final JdbcClient jdbc;

    public ConversationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Stores a customer message once (by wamid) in the customer's conversation, creating both when
     * needed. When {@code answerAfter} is set and the bot is in charge, the answer is (re)scheduled
     * for that moment, so a burst of messages gets one answer.
     *
     * @return the conversation id
     */
    public UUID recordCustomerMessage(TenantId tenant, String phoneNumberId, String waId, String wamid, String type,
                                      String text, Instant sentAt, Duration answerAfter) {
        UUID contact = jdbc.sql("""
                        INSERT INTO contact (tenant_id, wa_id) VALUES (:tenant, :waId)
                        ON CONFLICT (tenant_id, wa_id) DO UPDATE SET wa_id = excluded.wa_id
                        RETURNING id
                        """)
                .param("tenant", tenant.value())
                .param("waId", waId)
                .query(UUID.class).single();
        UUID conversation = jdbc.sql("""
                        INSERT INTO conversation (tenant_id, contact_id, phone_number_id)
                        VALUES (:tenant, :contact, :phone)
                        ON CONFLICT (tenant_id, contact_id, phone_number_id) DO UPDATE SET updated_at = now()
                        RETURNING id
                        """)
                .param("tenant", tenant.value())
                .param("contact", contact)
                .param("phone", phoneNumberId)
                .query(UUID.class).single();
        int inserted = jdbc.sql("""
                        INSERT INTO message (tenant_id, conversation_id, ref, direction, author, wamid, type, text, sent_at)
                        VALUES (:tenant, :conversation, :ref, 'IN', 'CUSTOMER', :wamid, :type, :text, :sentAt)
                        ON CONFLICT (conversation_id, ref) DO NOTHING
                        """)
                .param("tenant", tenant.value())
                .param("conversation", conversation)
                .param("ref", "msg:" + wamid)
                .param("wamid", wamid)
                .param("type", type)
                .param("text", text)
                .param("sentAt", OffsetDateTime.ofInstant(sentAt, ZoneOffset.UTC))
                .update();
        if (inserted > 0 && answerAfter != null) {
            jdbc.sql("""
                            UPDATE conversation
                            SET reply_due_at = now() + make_interval(secs => :seconds), reply_attempts = 0, updated_at = now()
                            WHERE id = :id AND state = 'BOT'
                            """)
                    .param("seconds", answerAfter.toMillis() / 1000.0)
                    .param("id", conversation)
                    .update();
        }
        return conversation;
    }

    /** Owner-role claim of the conversation whose answer is due first, leased for {@code lease}. */
    public Optional<ClaimedReply> claimDue(Duration lease) {
        return jdbc.sql("""
                        UPDATE conversation
                        SET reply_due_at = now() + make_interval(secs => :seconds), reply_attempts = reply_attempts + 1
                        WHERE id = (SELECT id FROM conversation WHERE reply_due_at <= now()
                                    ORDER BY reply_due_at LIMIT 1 FOR UPDATE SKIP LOCKED)
                        RETURNING id, tenant_id, reply_due_at, reply_attempts
                        """)
                .param("seconds", lease.toMillis() / 1000.0)
                .query((rs, row) -> new ClaimedReply(rs.getObject("id", UUID.class),
                        new TenantId(rs.getObject("tenant_id", UUID.class)),
                        rs.getObject("reply_due_at", OffsetDateTime.class),
                        rs.getInt("reply_attempts")))
                .optional();
    }

    /** Done; unless a new customer message rescheduled the answer meanwhile. */
    public void finish(ClaimedReply claim) {
        jdbc.sql("""
                        UPDATE conversation SET reply_due_at = NULL, reply_attempts = 0, last_error = NULL, updated_at = now()
                        WHERE id = :id AND reply_due_at = :claimed
                        """)
                .param("id", claim.conversationId())
                .param("claimed", claim.dueAt())
                .update();
    }

    /** Try again after {@code delay}; unless a new customer message rescheduled it meanwhile. */
    public void retryLater(ClaimedReply claim, Duration delay, String error) {
        jdbc.sql("""
                        UPDATE conversation
                        SET reply_due_at = now() + make_interval(secs => :seconds), last_error = :error, updated_at = now()
                        WHERE id = :id AND reply_due_at = :claimed
                        """)
                .param("seconds", delay.toMillis() / 1000.0)
                .param("error", error)
                .param("id", claim.conversationId())
                .param("claimed", claim.dueAt())
                .update();
    }

    public Optional<ConversationView> find(UUID conversation) {
        return jdbc.sql("""
                        SELECT c.id, c.phone_number_id, k.wa_id, c.state
                        FROM conversation c JOIN contact k ON k.id = c.contact_id
                        WHERE c.id = :id
                        """)
                .param("id", conversation)
                .query(ConversationView.class).optional();
    }

    /** The latest {@code limit} messages, oldest first. */
    public List<StoredMessage> recentMessages(UUID conversation, int limit) {
        List<StoredMessage> newestFirst = jdbc.sql("""
                        SELECT direction, author, wamid, text, sent_at FROM message
                        WHERE conversation_id = :id
                        ORDER BY sent_at DESC, created_at DESC
                        LIMIT :limit
                        """)
                .param("id", conversation)
                .param("limit", limit)
                .query((rs, row) -> new StoredMessage(rs.getString("direction"), rs.getString("author"),
                        rs.getString("wamid"), rs.getString("text"),
                        rs.getObject("sent_at", OffsetDateTime.class).toInstant()))
                .list();
        return newestFirst.reversed();
    }

    /**
     * Stores the bot's answer once, by {@code ref}.
     *
     * @return false when that answer was already stored (a retried reply)
     */
    public boolean recordBotMessage(TenantId tenant, UUID conversation, String ref, String text, Instant sentAt) {
        return jdbc.sql("""
                        INSERT INTO message (tenant_id, conversation_id, ref, direction, author, type, text, sent_at)
                        VALUES (:tenant, :conversation, :ref, 'OUT', 'BOT', 'text', :text, :sentAt)
                        ON CONFLICT (conversation_id, ref) DO NOTHING
                        """)
                .param("tenant", tenant.value())
                .param("conversation", conversation)
                .param("ref", ref)
                .param("text", text)
                .param("sentAt", OffsetDateTime.ofInstant(sentAt, ZoneOffset.UTC))
                .update() > 0;
    }
}
