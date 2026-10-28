package br.com.conversa.channel.whatsapp.webhook;

import java.util.Objects;

/**
 * Atomic event extracted from a webhook notification. One notification may contain several.
 *
 * @param eventKey      idempotency key; Meta redelivers notifications and duplicates are expected
 * @param type          event type
 * @param field         webhook field ({@code messages}, {@code smb_message_echoes}...)
 * @param wabaId        WhatsApp Business Account id ({@code entry.id})
 * @param phoneNumberId business's number; resolves the tenant (M3). May be null for {@link WebhookEventType#OTHER}
 * @param waId          customer's number (PII); null when not applicable
 * @param payloadJson   JSON with the original {@code metadata}, {@code contact} and {@code item}
 */
public record WebhookEvent(
        String eventKey,
        WebhookEventType type,
        String field,
        String wabaId,
        String phoneNumberId,
        String waId,
        String payloadJson) {

    public WebhookEvent {
        Objects.requireNonNull(eventKey, "eventKey");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payloadJson, "payloadJson");
    }
}
