package br.com.conversa.app.api;

import br.com.conversa.app.conversation.ConversationRepository;
import br.com.conversa.app.conversation.HandoffService;
import br.com.conversa.app.tenant.TenantService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import br.com.conversa.app.tenant.TenantTransactions;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Conversations with staff (ADR-008, D-015): the panel lists them, takes them over and hands them
 * back to the bot.
 */
@RestController
@RequestMapping("/api/v1/conversations")
class ConversationsController {

    record HandoffView(UUID id, String customerName, String customerPhone, String handoffReason,
                       OffsetDateTime handoffAt, OffsetDateTime pausedUntil, String lastMessage) {
    }

    private final JdbcClient jdbc;
    private final TenantTransactions tenants;
    private final ConversationRepository conversations;
    private final ApplicationEventPublisher events;
    private final HandoffService handoff;
    private final TenantService tenantService;
    private final ObjectMapper mapper;

    ConversationsController(JdbcClient jdbc, TenantTransactions tenants, ConversationRepository conversations,
                            ApplicationEventPublisher events, HandoffService handoff, TenantService tenantService,
                            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.conversations = conversations;
        this.events = events;
        this.handoff = handoff;
        this.tenantService = tenantService;
        this.mapper = mapper;
    }

    /**
     * @param state BOT or HUMAN (paused: staff are answering)
     */
    record ConversationSummary(UUID id, String customerName, String customerPhone, String state,
                               String handoffReason, OffsetDateTime pausedUntil, String lastMessage,
                               String lastAuthor, OffsetDateTime lastMessageAt) {
    }

    record MessageView(String direction, String author, String text, OffsetDateTime sentAt) {
    }

    /** @param state the pack's conversation state, such as the restaurant's cart being put together */
    record ConversationDetail(ConversationSummary conversation, List<MessageView> messages, JsonNode state) {
    }

    /**
     * Latest conversations first (D-013), optionally only those staff are handling ({@code state=HUMAN})
     * or matching a name or phone digits.
     */
    @GetMapping
    List<ConversationSummary> list(@AuthenticationPrincipal StaffUser user,
                                   @RequestParam(required = false) String state,
                                   @RequestParam(required = false) String q,
                                   @RequestParam(defaultValue = "50") int limit) {
        String search = q == null || q.isBlank() ? null : q.strip();
        return tenants.inTenant(user.tenant(), () -> jdbc.sql(SUMMARY + """
                        WHERE NOT c.simulated AND k.erased_at IS NULL
                          AND (CAST(:state AS text) IS NULL OR c.state = :state)
                          AND (CAST(:q AS text) IS NULL OR k.display_name ILIKE '%' || :q || '%' OR k.wa_id LIKE '%' || :q || '%')
                        ORDER BY last_message_at DESC NULLS LAST
                        LIMIT :limit
                        """)
                .param("state", "BOT".equals(state) || "HUMAN".equals(state) ? state : null)
                .param("q", search).param("limit", Math.clamp(limit, 1, 200))
                .query(ConversationSummary.class).list());
    }

    /** One conversation: its latest 100 messages (bot, staff and customer) and the order in progress. */
    @GetMapping("/{id}")
    ResponseEntity<ConversationDetail> detail(@AuthenticationPrincipal StaffUser user, @PathVariable UUID id) {
        return tenants.inTenant(user.tenant(), () -> jdbc.sql(SUMMARY + " WHERE c.id = :id AND NOT c.simulated")
                .param("id", id).query(ConversationSummary.class).optional()
                .map(summary -> {
                    List<MessageView> messages = jdbc.sql("""
                                    SELECT direction, author, text, sent_at FROM (
                                        SELECT direction, author, text, sent_at, created_at FROM message
                                        WHERE conversation_id = :id ORDER BY created_at DESC LIMIT 100) latest
                                    ORDER BY created_at
                                    """)
                            .param("id", id).query(MessageView.class).list();
                    String context = jdbc.sql("SELECT context::text FROM conversation WHERE id = :id")
                            .param("id", id).query(String.class).single();
                    JsonNode state = mapper.readTree(context);
                    return ResponseEntity.ok(new ConversationDetail(summary, messages, state));
                })
                .orElseGet(() -> ResponseEntity.notFound().build()));
    }

    private static final String SUMMARY = """
            SELECT c.id, k.display_name AS customer_name, k.wa_id AS customer_phone, c.state, c.handoff_reason,
                   c.paused_until, last.text AS last_message, last.author AS last_author,
                   last.sent_at AS last_message_at
            FROM conversation c JOIN contact k ON k.id = c.contact_id
            LEFT JOIN LATERAL (SELECT m.text, m.author, m.sent_at FROM message m WHERE m.conversation_id = c.id
                               ORDER BY m.created_at DESC LIMIT 1) last ON true
            """;

    /** Conversations staff are handling now, most recent handoff first. */
    @GetMapping("/handoffs")
    List<HandoffView> handoffs(@AuthenticationPrincipal StaffUser user) {
        return tenants.inTenant(user.tenant(), () -> jdbc.sql("""
                        SELECT c.id, k.display_name AS customer_name, k.wa_id AS customer_phone, c.handoff_reason,
                               c.handoff_at, c.paused_until,
                               (SELECT m.text FROM message m WHERE m.conversation_id = c.id
                                ORDER BY m.created_at DESC LIMIT 1) AS last_message
                        FROM conversation c JOIN contact k ON k.id = c.contact_id
                        WHERE c.tenant_id = :tenant AND NOT c.simulated AND c.state = 'HUMAN' AND c.paused_until > now()
                        ORDER BY c.handoff_at DESC
                        """)
                .param("tenant", user.tenant().value())
                .query(HandoffView.class).list());
    }

    /** "Assumir conversa": the bot stops answering this customer until staff hand it back. */
    @PostMapping("/{id}/takeover")
    ResponseEntity<Void> takeOver(@AuthenticationPrincipal StaffUser user, @PathVariable UUID id) {
        var config = tenantService.effectiveConfig(user.tenant());
        boolean found = tenants.inTenant(user.tenant(), () -> handoff.takeOver(user.tenant(), id, config));
        return found ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /** "Devolver ao bot": ends the pause now; a customer left waiting is answered. */
    @PostMapping("/{id}/resume")
    ResponseEntity<Void> resume(@AuthenticationPrincipal StaffUser user, @PathVariable UUID id) {
        tenants.runInTenant(user.tenant(), () -> {
            conversations.resume(id);
            events.publishEvent(PanelEvent.resumed(user.tenant(), id));
        });
        return ResponseEntity.noContent().build();
    }
}
