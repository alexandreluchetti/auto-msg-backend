package br.com.conversa.app.inbound;

import br.com.conversa.app.inbound.InboundEventQueue.ClaimedInbound;
import br.com.conversa.app.outbound.OutboundMessageRepository;
import br.com.conversa.app.outbound.OutboundRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns one inbound event into its effects, inside the worker's transaction. Every effect is
 * idempotent, so an event processed twice (crash before commit, redelivery) changes nothing.
 *
 * <p>Until the conversation engine exists (M5), customer messages only feed the echo bot, a
 * development aid for the Cloud API test number that is off by default. Echoes from the app and
 * other fields are only stored; handoff uses them in M6.
 */
@Component
class InboundEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(InboundEventProcessor.class);

    /** Graph API limit for a text body is 4096 characters; keep room for the prefix. */
    private static final int MAX_ECHOED_CHARS = 4000;

    private final OutboundMessageRepository outbound;
    private final ObjectMapper mapper;
    private final boolean echoBotEnabled;

    InboundEventProcessor(OutboundMessageRepository outbound, ObjectMapper mapper,
                          @Value("${conversa.echo-bot.enabled:false}") boolean echoBotEnabled) {
        this.outbound = outbound;
        this.mapper = mapper;
        this.echoBotEnabled = echoBotEnabled;
    }

    void process(ClaimedInbound event) {
        JsonNode item = mapper.readTree(event.payloadJson()).path("item");
        switch (event.type()) {
            case MESSAGE -> {
                if (event.tenantId() == null) {
                    log.warn("Inbound event {} ignored: no tenant registered for its number", event.id());
                } else if (echoBotEnabled) {
                    echo(event, item);
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

    /**
     * Marks the message as read and repeats its text. The read receipt is sent only because the bot
     * answers: marking read also clears the unread badge staff rely on in the app.
     */
    private void echo(ClaimedInbound event, JsonNode message) {
        if (!"text".equals(message.path("type").asString())) {
            return;
        }
        String wamid = message.path("id").asString();
        String body = message.path("text").path("body").asString();
        if (body.length() > MAX_ECHOED_CHARS) {
            body = body.substring(0, MAX_ECHOED_CHARS);
        }
        outbound.enqueue("read:" + wamid, event.tenantId(),
                OutboundRequest.readReceipt(event.phoneNumberId(), event.waId(), wamid, mapper));
        outbound.enqueue("echo-bot:" + wamid, event.tenantId(),
                OutboundRequest.text(event.phoneNumberId(), event.waId(), "Recebemos: " + body, mapper));
    }

    private static String textOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asString();
    }
}
