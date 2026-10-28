package br.com.conversa.channel.whatsapp.webhook;

public enum WebhookEventType {
    /** Mensagem enviada pelo cliente. */
    MESSAGE,
    /** Status de uma mensagem enviada por nós (sent, delivered, read, failed). */
    STATUS,
    /** Mensagem enviada pela equipe pelo app WhatsApp Business (coexistência). */
    ECHO,
    /** Demais campos (account_update, history, smb_app_state_sync...), guardados para auditoria. */
    OTHER
}
