package br.com.conversa.app.inbound;

import br.com.conversa.app.conversation.AssistantProperties;
import br.com.conversa.app.conversation.ConversationRepository;
import br.com.conversa.app.inbound.InboundEventQueue.ClaimedInbound;
import br.com.conversa.app.outbound.OutboundMessageRepository;
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
 * Echoes from the app and other fields are only stored; handoff uses them in M6.
 */
@Component
class InboundEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(InboundEventProcessor.class);

    private final OutboundMessageRepository outbound;
    private final ConversationRepository conversations;
    private final TenantTransactions tenants;
    private final AssistantProperties assistant;
    private final LlmClient llm;
    private final ObjectMapper mapper;

    InboundEventProcessor(OutboundMessageRepository outbound, ConversationRepository conversations,
                          TenantTransactions tenants, AssistantProperties assistant, LlmClient llm,
                          ObjectMapper mapper) {
        this.outbound = outbound;
        this.conversations = conversations;
        this.tenants = tenants;
        this.assistant = assistant;
        this.llm = llm;
        this.mapper = mapper;
    }

    void process(ClaimedInbound event) {
        JsonNode item = mapper.readTree(event.payloadJson()).path("item");
        switch (event.type()) {
            case MESSAGE -> {
                if (event.tenantId() == null) {
                    log.warn("Inbound event {} ignored: no tenant registered for its number", event.id());
                } else {
                    recordCustomerMessage(event, item);
                }
            }
            case STATUS -> outbound.updateDeliveryStatus(
                    item.path("id").asString(),
                    item.path("status").asString(),
                    textOrNull(item.path("pricing").path("category")),
                    item.path("pricing").path("billable").isBoolean()
                            ? item.path("pricing").path("billable").asBoolean() : null);
            case ECHO, OTHER -> {
                // Stored only; see the class comment.
            }
        }
    }

    private void recordCustomerMessage(ClaimedInbound event, JsonNode message) {
        String timestamp = message.path("timestamp").asString();
        Instant sentAt = timestamp.matches("\\d+") ? Instant.ofEpochSecond(Long.parseLong(timestamp)) : Instant.now();
        boolean answer = assistant.enabled() && llm.configured();
        tenants.runInTenant(event.tenantId(), () -> conversations.recordCustomerMessage(event.tenantId(),
                event.phoneNumberId(), event.waId(), message.path("id").asString(), message.path("type").asString(),
                CustomerText.of(message), sentAt, answer ? assistant.debounce() : null));
    }

    private static String textOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asString();
    }
}
