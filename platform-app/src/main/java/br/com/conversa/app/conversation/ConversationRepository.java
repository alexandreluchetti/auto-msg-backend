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
 * Contacts, conversations and their messages. Everything except {@link #claimDue} and the claim
 * bookkeeping runs inside the caller's tenant context (RLS); claiming is cross-tenant work of the
 * reply worker (ADR-010).
 */
@Repository
public class ConversationRepository {

    /** A stored message as the engine reads it back. */
    /** @param replyId id of the button the customer tapped, if the message is a tap */
    public record StoredMessage(String direction, String author, String wamid, String text, Instant sentAt,
                                String replyId) {

        public StoredMessage(String direction, String author, String wamid, String text, Instant sentAt) {
            this(direction, author, wamid, text, sentAt, null);
        }

        public boolean fromCustomer() {
            return "IN".equals(direction);
        }
    }

    public record ConversationView(UUID id, String phoneNumberId, String waId, String displayName, String state,
                                   int failureStreak) {
    }

    /** A conversation reserved for one answer; {@code dueAt} is the lease the claim wrote. */
    public record ClaimedReply(UUID conversationId, TenantId tenant, OffsetDateTime dueAt, int attempts) {
    }

    private final JdbcClient jdbc;

    public ConversationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The conversation a customer message went to, and whether the message was new. */
    public record RecordedMessage(UUID conversationId, boolean isNew) {
    }

    /**
     * Stores a customer message once (by wamid) in the customer's conversation, creating both when
     * needed. A pause that has expired ends here: the bot takes the conversation back. When
     * {@code answerAfter} is set and the bot is in charge, the answer is (re)scheduled for that
     * moment, so a burst of messages gets one answer.
     *
     * @return the conversation and whether the message was new
     */
    public RecordedMessage recordCustomerMessage(TenantId tenant, String phoneNumberId, String waId, String displayName,
                                      String wamid, String type, String text, String replyId, Instant sentAt,
                                      Duration answerAfter) {
        UUID conversation = conversationOf(tenant, phoneNumberId, waId, displayName);
        boolean inserted = insertMessage(tenant, conversation, "msg:" + wamid, "IN", "CUSTOMER", wamid, type, text,
                replyId, sentAt);
        if (inserted) {
            jdbc.sql("""
                            UPDATE conversation SET state = 'BOT', paused_until = NULL, updated_at = now()
                            WHERE id = :id AND state = 'HUMAN' AND paused_until <= now()
                            """)
                    .param("id", conversation)
                    .update();
        }
        if (inserted && answerAfter != null) {
            jdbc.sql("""
                            UPDATE conversation
                            SET reply_due_at = now() + make_interval(secs => :seconds), reply_attempts = 0, updated_at = now()
                            WHERE id = :id AND state = 'BOT'
                            """)
                    .param("seconds", answerAfter.toMillis() / 1000.0)
                    .param("id", conversation)
                    .update();
        }
        return new RecordedMessage(conversation, inserted);
    }

    /**
     * Stores a message staff sent from the WhatsApp Business app (an echo) once, by wamid.
     *
     * @return the conversation id, and whether the echo was new
     */
    public StaffEcho recordStaffEcho(TenantId tenant, String phoneNumberId, String waId, String wamid, String type,
                                     String text, Instant sentAt) {
        UUID conversation = conversationOf(tenant, phoneNumberId, waId, null);
        return new StaffEcho(conversation,
                insertMessage(tenant, conversation, "echo:" + wamid, "OUT", "STAFF", wamid, type, text, null, sentAt));
    }

    /**
     * Remembers the media of a customer message for the reply worker (H6). Voice notes are marked
     * pending until transcribed; images are read again for each answer attempt, never stored.
     */
    public void attachMedia(UUID conversation, String wamid, String mediaId, String mimeType, boolean transcribe) {
        jdbc.sql("""
                        UPDATE message SET media_id = :mediaId, media_mime = :mime,
                                           media_status = CASE WHEN :transcribe THEN 'PENDING' END
                        WHERE conversation_id = :conversation AND ref = :ref
                        """)
                .param("mediaId", mediaId).param("mime", mimeType).param("transcribe", transcribe)
                .param("conversation", conversation).param("ref", "msg:" + wamid)
                .update();
    }

    public record StaffEcho(UUID conversationId, boolean isNew) {
    }

    /**
     * Hands the conversation to staff until {@code until}: the bot stops answering and any pending
     * answer is cancelled.
     */
    public void pause(UUID conversation, Instant until, String reason) {
        jdbc.sql("""
                        UPDATE conversation
                        SET state = 'HUMAN', paused_until = :until, handoff_reason = :reason, handoff_at = now(),
                            reply_due_at = NULL, failure_streak = 0, updated_at = now()
                        WHERE id = :id
                        """)
                .param("until", OffsetDateTime.ofInstant(until, ZoneOffset.UTC))
                .param("reason", reason)
                .param("id", conversation)
                .update();
    }

    /**
     * Ends a pause now (the panel's "hand back to bot", D-015). If the customer's latest message is
     * still unanswered, the bot answers it right away.
     */
    public void resume(UUID conversation) {
        jdbc.sql("""
                        UPDATE conversation c
                        SET state = 'BOT', paused_until = NULL, failure_streak = 0, reply_attempts = 0,
                            reply_due_at = CASE WHEN (SELECT m.author FROM message m WHERE m.conversation_id = c.id
                                                      ORDER BY m.created_at DESC LIMIT 1) = 'CUSTOMER'
                                                THEN now() END,
                            updated_at = now()
                        WHERE id = :id AND state = 'HUMAN'
                        """)
                .param("id", conversation)
                .update();
    }

    /** Consecutive answers the bot could not give; 0 after a good one. */
    public void setFailureStreak(UUID conversation, int streak) {
        jdbc.sql("UPDATE conversation SET failure_streak = :streak, updated_at = now() WHERE id = :id")
                .param("streak", streak)
                .param("id", conversation)
                .update();
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
        return find(conversation, false);
    }

    /** Same as {@link #find(UUID)}, locking the row until the transaction ends. */
    public Optional<ConversationView> findForUpdate(UUID conversation) {
        return find(conversation, true);
    }

    private Optional<ConversationView> find(UUID conversation, boolean lock) {
        return jdbc.sql("""
                        SELECT c.id, c.phone_number_id, k.wa_id, k.display_name, c.state, c.failure_streak
                        FROM conversation c JOIN contact k ON k.id = c.contact_id
                        WHERE c.id = :id
                        """ + (lock ? " FOR UPDATE OF c" : ""))
                .param("id", conversation)
                .query(ConversationView.class).optional();
    }

    /**
     * The latest {@code limit} messages, oldest first, in the order they were stored: Meta's
     * timestamps have one-second resolution, too coarse to order a customer message after the
     * bot's answer to the previous one.
     */
    public List<StoredMessage> recentMessages(UUID conversation, int limit) {
        List<StoredMessage> newestFirst = jdbc.sql("""
                        SELECT direction, author, wamid, text, sent_at, reply_id FROM message
                        WHERE conversation_id = :id
                        ORDER BY created_at DESC
                        LIMIT :limit
                        """)
                .param("id", conversation)
                .param("limit", limit)
                .query((rs, row) -> new StoredMessage(rs.getString("direction"), rs.getString("author"),
                        rs.getString("wamid"), rs.getString("text"),
                        rs.getObject("sent_at", OffsetDateTime.class).toInstant(), rs.getString("reply_id")))
                .list();
        return newestFirst.reversed();
    }

    /**
     * Stores the bot's answer once, by {@code ref}.
     *
     * @return false when that answer was already stored (a retried reply)
     */
    public boolean recordBotMessage(TenantId tenant, UUID conversation, String ref, String text, Instant sentAt) {
        return insertMessage(tenant, conversation, ref, "OUT", "BOT", null, "text", text, null, sentAt);
    }

    private UUID conversationOf(TenantId tenant, String phoneNumberId, String waId, String displayName) {
        UUID contact = jdbc.sql("""
                        INSERT INTO contact (tenant_id, wa_id, display_name) VALUES (:tenant, :waId, :name)
                        ON CONFLICT (tenant_id, wa_id)
                            DO UPDATE SET display_name = coalesce(excluded.display_name, contact.display_name)
                        RETURNING id
                        """)
                .param("tenant", tenant.value())
                .param("waId", waId)
                .param("name", displayName)
                .query(UUID.class).single();
        return jdbc.sql("""
                        INSERT INTO conversation (tenant_id, contact_id, phone_number_id)
                        VALUES (:tenant, :contact, :phone)
                        ON CONFLICT (tenant_id, contact_id, phone_number_id) DO UPDATE SET updated_at = now()
                        RETURNING id
                        """)
                .param("tenant", tenant.value())
                .param("contact", contact)
                .param("phone", phoneNumberId)
                .query(UUID.class).single();
    }

    private boolean insertMessage(TenantId tenant, UUID conversation, String ref, String direction, String author,
                                  String wamid, String type, String text, String replyId, Instant sentAt) {
        return jdbc.sql("""
                        INSERT INTO message (tenant_id, conversation_id, ref, direction, author, wamid, type, text, reply_id,
                                             sent_at)
                        VALUES (:tenant, :conversation, :ref, :direction, :author, :wamid, :type, :text, :replyId,
                                :sentAt)
                        ON CONFLICT (conversation_id, ref) DO NOTHING
                        """)
                .param("tenant", tenant.value())
                .param("conversation", conversation)
                .param("ref", ref)
                .param("direction", direction)
                .param("author", author)
                .param("wamid", wamid)
                .param("type", type)
                .param("text", text)
                .param("replyId", replyId)
                .param("sentAt", OffsetDateTime.ofInstant(sentAt, ZoneOffset.UTC))
                .update() > 0;
    }
}
