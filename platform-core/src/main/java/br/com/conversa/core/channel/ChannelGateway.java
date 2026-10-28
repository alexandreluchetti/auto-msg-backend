package br.com.conversa.core.channel;

/**
 * Port to the messaging channel. Implementations never throw for channel errors: they return
 * {@link SendResult.Failed} so the caller decides about retries.
 *
 * @see OutboundMessage
 */
public interface ChannelGateway {

    /**
     * @param senderId the business's sending account (WhatsApp: the {@code phone_number_id})
     */
    SendResult send(String senderId, OutboundMessage message);

    /** Marks a received message as read, which also marks the earlier ones in the chat. */
    SendResult markAsRead(String senderId, String messageId);
}
