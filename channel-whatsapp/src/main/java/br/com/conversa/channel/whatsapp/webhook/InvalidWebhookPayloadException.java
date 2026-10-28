package br.com.conversa.channel.whatsapp.webhook;

public class InvalidWebhookPayloadException extends RuntimeException {

    public InvalidWebhookPayloadException(String message) {
        super(message);
    }

    public InvalidWebhookPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
