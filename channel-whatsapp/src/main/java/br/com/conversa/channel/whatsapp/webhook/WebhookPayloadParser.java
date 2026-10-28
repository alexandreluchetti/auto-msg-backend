package br.com.conversa.channel.whatsapp.webhook;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Divide uma notificação do webhook da Cloud API em eventos atômicos com chave de idempotência.
 * Não interpreta o conteúdo das mensagens; isso é papel do worker (M2+).
 */
public final class WebhookPayloadParser {

    private static final String OBJECT_TYPE = "whatsapp_business_account";

    private final ObjectMapper mapper;

    public WebhookPayloadParser() {
        this(JsonMapper.builder().build());
    }

    public WebhookPayloadParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @throws InvalidWebhookPayloadException se o corpo não for JSON ou não for uma notificação de WABA
     */
    public List<WebhookEvent> parse(byte[] rawBody) {
        JsonNode root;
        try {
            root = mapper.readTree(rawBody);
        } catch (JacksonException e) {
            throw new InvalidWebhookPayloadException("Corpo do webhook não é JSON válido", e);
        }
        if (root == null || !OBJECT_TYPE.equals(textOrNull(root.path("object")))) {
            throw new InvalidWebhookPayloadException("Objeto do webhook não suportado");
        }

        List<WebhookEvent> events = new ArrayList<>();
        for (JsonNode entry : root.path("entry")) {
            String wabaId = textOrNull(entry.path("id"));
            for (JsonNode change : entry.path("changes")) {
                parseChange(wabaId, change, events);
            }
        }
        return events;
    }

    private void parseChange(String wabaId, JsonNode change, List<WebhookEvent> events) {
        String field = textOrNull(change.path("field"));
        JsonNode value = change.path("value");
        JsonNode metadata = value.path("metadata");
        String phoneNumberId = textOrNull(metadata.path("phone_number_id"));

        switch (field == null ? "" : field) {
            case "messages" -> {
                for (JsonNode message : value.path("messages")) {
                    String waId = textOrNull(message.path("from"));
                    events.add(event("msg:" + textOrNull(message.path("id")), WebhookEventType.MESSAGE,
                            field, wabaId, phoneNumberId, waId, metadata, contactFor(value, waId), message));
                }
                for (JsonNode status : value.path("statuses")) {
                    String key = "status:" + textOrNull(status.path("id")) + ":" + textOrNull(status.path("status"));
                    events.add(event(key, WebhookEventType.STATUS, field, wabaId, phoneNumberId,
                            textOrNull(status.path("recipient_id")), metadata, null, status));
                }
            }
            case "smb_message_echoes" -> {
                for (JsonNode echo : value.path("message_echoes")) {
                    events.add(event("echo:" + textOrNull(echo.path("id")), WebhookEventType.ECHO,
                            field, wabaId, phoneNumberId, textOrNull(echo.path("to")), metadata, null, echo));
                }
            }
            // Conteúdo sem id próprio: a chave é o hash do change, estável entre reentregas.
            default -> events.add(event(
                    (field == null ? "unknown" : field) + ":" + sha256(change.toString()),
                    WebhookEventType.OTHER, field, wabaId, phoneNumberId, null, metadata, null, value));
        }
    }

    private WebhookEvent event(String key, WebhookEventType type, String field, String wabaId,
                               String phoneNumberId, String waId, JsonNode metadata, JsonNode contact,
                               JsonNode item) {
        ObjectNode payload = mapper.createObjectNode();
        payload.set("metadata", metadata.isMissingNode() ? null : metadata);
        payload.set("contact", contact);
        payload.set("item", item);
        return new WebhookEvent(key, type, field, wabaId, phoneNumberId, waId, payload.toString());
    }

    private static JsonNode contactFor(JsonNode value, String waId) {
        for (JsonNode contact : value.path("contacts")) {
            if (waId != null && waId.equals(textOrNull(contact.path("wa_id")))) {
                return contact;
            }
        }
        return null;
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asString();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
