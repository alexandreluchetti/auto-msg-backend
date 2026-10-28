package br.com.conversa.app.conversation;

import br.com.conversa.app.conversation.ConversationRepository.ConversationView;
import br.com.conversa.app.outbound.OutboundMessageRepository;
import br.com.conversa.app.outbound.OutboundRequest;
import br.com.conversa.core.tenant.TenantId;
import br.com.conversa.core.text.SearchText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Hands conversations to the restaurant's staff, who answer in the WhatsApp Business app
 * (ADR-008): the bot pauses for {@code handoff.pauseMinutes} of the tenant configuration and takes
 * the conversation back on the first customer message after that. Must run inside the tenant's
 * transaction.
 *
 * <ul>
 *   <li>A message staff send from the app (echo) pauses the bot; every new echo renews the pause.</li>
 *   <li>The customer asking for a person, the assistant asking for help ({@code request_human})
 *       or repeated failures pause it too, tell the customer and alert the staff numbers.</li>
 * </ul>
 */
@Component
public class HandoffService {

    private static final Logger log = LoggerFactory.getLogger(HandoffService.class);

    static final String CUSTOMER_NOTICE = "Certo! Vou chamar alguém da equipe para continuar o seu atendimento "
            + "por aqui. É só aguardar um pouquinho.";

    /**
     * Customer messages that plainly ask for a person, after normalization ("Quero falar com um
     * atendente!", "humano por favor"). Anything subtler is left to the assistant's tool.
     */
    private static final Pattern ASKS_FOR_PERSON = Pattern.compile(
            "^(?:.*\\b(?:falar|conversar|fala|chama|chamar|passa|passar|transfere|transferir)\\b.*\\b"
                    + "(?:atendente|humano|humana|pessoa|alguem da equipe|funcionario|funcionaria|gerente|dono|dona)\\b.*"
                    + "|\\s*(?:atendente|atendimento humano|humano|falar com atendente)\\s*(?:por favor|pf|pfv)?\\s*)$");

    private static final int MAX_PARAMETER = 60;

    private final ConversationRepository conversations;
    private final OutboundMessageRepository outbound;
    private final HandoffProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;

    HandoffService(ConversationRepository conversations, OutboundMessageRepository outbound,
                   HandoffProperties properties, ObjectMapper mapper, Clock clock) {
        this.conversations = conversations;
        this.outbound = outbound;
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Whether the customer's message plainly asks for a person. */
    public static boolean asksForPerson(String customerText) {
        return ASKS_FOR_PERSON.matcher(SearchText.normalize(customerText)).matches();
    }

    /** Records a message staff sent from the app and pauses the bot (renewing an earlier pause). */
    public void onStaffEcho(TenantId tenant, String phoneNumberId, String customerWaId, String wamid, String type,
                            String text, Instant sentAt, JsonNode config) {
        var echo = conversations.recordStaffEcho(tenant, phoneNumberId, customerWaId, wamid, type, text, sentAt);
        if (echo.isNew()) {
            conversations.pause(echo.conversationId(), pauseUntil(config), HandoffReason.STAFF_REPLY.name());
        }
    }

    /**
     * Pauses the bot, tells the customer that staff will continue and alerts the staff numbers.
     * Idempotent per {@code lastCustomerWamid}.
     */
    public void handOver(TenantId tenant, ConversationView conversation, String lastCustomerWamid,
                         HandoffReason reason, String detail, JsonNode config) {
        String ref = "handoff:" + conversation.id() + ":" + lastCustomerWamid;
        if (conversations.recordBotMessage(tenant, conversation.id(), ref, CUSTOMER_NOTICE, clock.instant())) {
            outbound.enqueue("read:" + lastCustomerWamid, tenant, OutboundRequest.readReceipt(
                    conversation.phoneNumberId(), conversation.waId(), lastCustomerWamid, mapper));
            outbound.enqueue(ref, tenant, OutboundRequest.text(
                    conversation.phoneNumberId(), conversation.waId(), CUSTOMER_NOTICE, mapper));
            alertStaff(tenant, conversation, ref, reason, detail, config);
        }
        conversations.pause(conversation.id(), pauseUntil(config), reason.name());
        log.info("Conversation {} handed to staff: {}", conversation.id(), reason);
    }

    private void alertStaff(TenantId tenant, ConversationView conversation, String ref, HandoffReason reason,
                            String detail, JsonNode config) {
        if (properties.staffAlertTemplate() == null || properties.staffAlertTemplate().isBlank()) {
            log.warn("No staff alert template configured (conversa.handoff.staff-alert-template); staff of tenant {} "
                    + "see the handoff only in the app and the panel", tenant);
            return;
        }
        String customer = customerLabel(conversation);
        String why = detail == null || detail.isBlank() ? reason.label() : reason.label() + ": " + detail;
        for (JsonNode staff : config.path("staffNumbers")) {
            outbound.enqueue("alert:" + ref + ":" + staff.asString(), tenant, OutboundRequest.template(
                    conversation.phoneNumberId(), staff.asString(), properties.staffAlertTemplate(),
                    properties.staffAlertLanguage(), List.of(parameter(customer), parameter(why)), mapper));
        }
    }

    /** Name the customer uses on WhatsApp and the last 4 digits of the number, never the whole number. */
    static String customerLabel(ConversationView conversation) {
        String waId = conversation.waId();
        String digits = waId.length() > 4 ? waId.substring(waId.length() - 4) : waId;
        String name = conversation.displayName();
        return (name == null || name.isBlank() ? "Cliente" : name.strip()) + " (final " + digits + ")";
    }

    /** Template parameters cannot hold line breaks or long runs of spaces. */
    private static String parameter(String value) {
        String flat = value.replaceAll("\\s+", " ").strip();
        return flat.length() > MAX_PARAMETER ? flat.substring(0, MAX_PARAMETER - 1) + "…" : flat;
    }

    private Instant pauseUntil(JsonNode config) {
        int minutes = config.path("handoff").path("pauseMinutes").asInt(60);
        return clock.instant().plus(Duration.ofMinutes(minutes));
    }
}
