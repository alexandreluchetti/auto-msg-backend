package br.com.conversa.channel.whatsapp.webhook;

import java.util.Objects;

/**
 * Evento atômico extraído de uma notificação do webhook. Uma notificação pode conter vários.
 *
 * @param eventKey      chave de idempotência; a Meta reentrega notificações e duplicatas são esperadas
 * @param type          tipo do evento
 * @param field         campo do webhook ({@code messages}, {@code smb_message_echoes}...)
 * @param wabaId        id da WhatsApp Business Account ({@code entry.id})
 * @param phoneNumberId número da empresa; resolve o tenant (M3). Pode ser nulo em {@link WebhookEventType#OTHER}
 * @param waId          número do cliente (PII); nulo quando não se aplica
 * @param payloadJson   JSON com {@code metadata}, {@code contact} e {@code item} originais
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
