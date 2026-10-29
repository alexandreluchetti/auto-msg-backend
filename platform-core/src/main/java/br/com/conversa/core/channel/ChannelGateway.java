package br.com.conversa.core.channel;

import java.util.Optional;

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

    /**
     * Marks a received message as read and shows "typing..." to the customer until the next
     * message or for a few seconds (WhatsApp: 25 s). Only when an answer follows.
     */
    SendResult showTyping(String senderId, String messageId);

    /**
     * Downloads a file a customer sent ({@code mediaId} from the webhook).
     *
     * @return empty when it cannot be downloaded or is larger than {@code maxBytes}
     */
    Optional<MediaContent> download(String senderId, String mediaId, long maxBytes);
}
