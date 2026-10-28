package br.com.conversa.core.channel;

/**
 * Outcome of a call to the channel. A retryable failure may succeed later (rate limit, outage);
 * a permanent one will not (invalid recipient, window closed).
 */
public sealed interface SendResult {

    /** @param messageId id assigned by the channel (WhatsApp: the {@code wamid}); null for read receipts */
    record Sent(String messageId) implements SendResult {
    }

    record Failed(boolean retryable, String code, String detail) implements SendResult {
    }
}
