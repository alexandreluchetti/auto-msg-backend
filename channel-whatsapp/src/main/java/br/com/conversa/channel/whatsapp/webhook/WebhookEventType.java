package br.com.conversa.channel.whatsapp.webhook;

public enum WebhookEventType {
    /** Message sent by the customer. */
    MESSAGE,
    /** Status of a message we sent (sent, delivered, read, failed). */
    STATUS,
    /** Message sent by staff from the WhatsApp Business app (coexistence). */
    ECHO,
    /** Other fields (account_update, history, smb_app_state_sync...), kept for auditing. */
    OTHER
}
