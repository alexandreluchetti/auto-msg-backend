package br.com.conversa.app.api;

import br.com.conversa.app.conversation.ConversationRepository;
import br.com.conversa.app.conversation.HandoffService;
import br.com.conversa.app.tenant.TenantService;
import br.com.conversa.app.tenant.TenantTransactions;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    ConversationsController(JdbcClient jdbc, TenantTransactions tenants, ConversationRepository conversations,
                            ApplicationEventPublisher events, HandoffService handoff, TenantService tenantService) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.conversations = conversations;
        this.events = events;
        this.handoff = handoff;
        this.tenantService = tenantService;
    }

    /** Conversations staff are handling now, most recent handoff first. */
    @GetMapping("/handoffs")
    List<HandoffView> handoffs(@AuthenticationPrincipal StaffUser user) {
        return tenants.inTenant(user.tenant(), () -> jdbc.sql("""
                        SELECT c.id, k.display_name AS customer_name, k.wa_id AS customer_phone, c.handoff_reason,
                               c.handoff_at, c.paused_until,
                               (SELECT m.text FROM message m WHERE m.conversation_id = c.id
                                ORDER BY m.created_at DESC LIMIT 1) AS last_message
                        FROM conversation c JOIN contact k ON k.id = c.contact_id
                        WHERE c.tenant_id = :tenant AND c.state = 'HUMAN' AND c.paused_until > now()
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
