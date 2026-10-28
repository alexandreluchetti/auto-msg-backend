package br.com.conversa.app.inbound;

import br.com.conversa.app.conversation.AssistantProperties;
import br.com.conversa.app.conversation.ConversationRepository;
import br.com.conversa.app.conversation.HandoffService;
import br.com.conversa.app.inbound.InboundEventQueue.ClaimedInbound;
import br.com.conversa.app.outbound.OutboundMessageRepository;
import br.com.conversa.app.tenant.TenantService;
import br.com.conversa.app.tenant.TenantTransactions;
import br.com.conversa.core.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * Turns one inbound event into its effects, inside the worker's transaction. Every effect is
 * idempotent, so an event processed twice (crash before commit, redelivery) changes nothing.
 *
 * <p>Customer messages go to their conversation, and the bot's answer is scheduled after the
 * debounce when the assistant is on and an LLM is configured ({@code ReplyWorker} answers).
 * A message staff send from the WhatsApp Business app (echo) is stored and pauses the bot
 * (handoff, ADR-008). Other fields are only stored.
 */
@Component
class InboundEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(InboundEventProcessor.class);

    private final OutboundMessageRepository outbound;
    private final ConversationRepository conversations;
    private final TenantTransactions tenants;
    private final AssistantProperties assistant;
    private final LlmClient llm;
    private final HandoffService handoff;
    private final TenantService tenantService;
    private final ObjectMapper mapper;

    InboundEventProcessor(OutboundMessageRepository outbound, ConversationRepository conversations,
                          TenantTransactions tenants, AssistantProperties assistant, LlmClient llm,
                          HandoffService handoff, TenantService tenantService,
                          ObjectMapper mapper) {
        this.outbound = outbound;
        this.conversations = conversations;
        this.tenants = tenants;
        this.assistant = assistant;
        this.llm = llm;
        this.handoff = handoff;
        this.tenantService = tenantService;
        this.mapper = mapper;
    }

    void process(ClaimedInbound event) {
        JsonNode payload = mapper.readTree(event.payloadJson());
        JsonNode item = payload.path("item");
        switch (event.type()) {
            case MESSAGE -> {
                if (event.tenantId() == null) {
                    log.warn("Inbound event {} ignored: no tenant registered for its number", event.id());
                } else {
                    recordCustomerMessage(event, item, payload.path("contact").path("profile").path("name"));
                }
            }
            case STATUS -> outbound.updateDeliveryStatus(
                    item.path("id").asString(),
                    item.path("status").asString(),
                    textOrNull(item.path("pricing").path("category")),
                    item.path("pricing").path("billable").isBoolean()
                            ? item.path("pricing").path("billable").asBoolean() : null);
            case ECHO -> {
                if (event.tenantId() != null) {
                    tenants.runInTenant(event.tenantId(), () -> handoff.onStaffEcho(event.tenantId(),
                            event.phoneNumberId(), event.waId(), item.path("id").asString(),
                            item.path("type").asString(), CustomerText.of(item), sentAt(item),
                            tenantService.effectiveConfig(event.tenantId())));
                }
            }
            case OTHER -> {
                // Stored only; see the class comment.
            }
        }
    }

    private void recordCustomerMessage(ClaimedInbound event, JsonNode message, JsonNode profileName) {
        Instant sentAt = sentAt(message);
        boolean answer = assistant.enabled() && llm.configured();
        tenants.runInTenant(event.tenantId(), () -> conversations.recordCustomerMessage(event.tenantId(),
                event.phoneNumberId(), event.waId(), profileName.isString() ? profileName.asString() : null,
                message.path("id").asString(), message.path("type").asString(),
                CustomerText.of(message), CustomerText.replyId(message), sentAt, answer ? assistant.debounce() : null));
    }

    /** Meta's timestamp (epoch seconds) of a message or echo. */
    private static Instant sentAt(JsonNode message) {
        String timestamp = message.path("timestamp").asString();
        return timestamp.matches("\\d+") ? Instant.ofEpochSecond(Long.parseLong(timestamp)) : Instant.now();
    }

    private static String textOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asString();
    }
}
