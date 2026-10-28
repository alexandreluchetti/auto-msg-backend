package br.com.conversa.app.outbound;

import br.com.conversa.core.channel.OutboundMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Outbox row as the sender sees it. The payload is channel-neutral; the gateway turns it into the
 * wire format.
 */
public record OutboundRequest(OutboundKind kind, String phoneNumberId, String waId, String payloadJson) {

    public static OutboundRequest text(String phoneNumberId, String waId, String body, ObjectMapper mapper) {
        return new OutboundRequest(OutboundKind.TEXT, phoneNumberId, waId,
                mapper.createObjectNode().put("body", body).toString());
    }

    public static OutboundRequest template(String phoneNumberId, String waId, String name, String languageCode,
                                           List<String> parameters, ObjectMapper mapper) {
        ObjectNode payload = mapper.createObjectNode().put("name", name).put("language", languageCode);
        ArrayNode values = payload.putArray("parameters");
        parameters.forEach(values::add);
        return new OutboundRequest(OutboundKind.TEMPLATE, phoneNumberId, waId, payload.toString());
    }

    public static OutboundRequest readReceipt(String phoneNumberId, String waId, String messageId,
                                              ObjectMapper mapper) {
        return new OutboundRequest(OutboundKind.READ, phoneNumberId, waId,
                mapper.createObjectNode().put("message_id", messageId).toString());
    }

    /** Only for {@link OutboundKind#TEXT} and {@link OutboundKind#TEMPLATE}. */
    OutboundMessage toMessage(ObjectMapper mapper) {
        JsonNode payload = mapper.readTree(payloadJson);
        return switch (kind) {
            case TEXT -> new OutboundMessage.Text(waId, payload.path("body").asString());
            case TEMPLATE -> {
                List<String> parameters = new ArrayList<>();
                payload.path("parameters").forEach(value -> parameters.add(value.asString()));
                yield new OutboundMessage.Template(waId, payload.path("name").asString(),
                        payload.path("language").asString(), parameters);
            }
            case READ -> throw new IllegalStateException("A read receipt is not a message");
        };
    }

    String readMessageId(ObjectMapper mapper) {
        return mapper.readTree(payloadJson).path("message_id").asString();
    }
}
