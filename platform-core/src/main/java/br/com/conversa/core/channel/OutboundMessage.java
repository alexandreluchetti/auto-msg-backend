package br.com.conversa.core.channel;

import java.util.List;
import java.util.Objects;

/**
 * Message the platform sends to a customer, independent of the channel's wire format.
 * Free text is only allowed inside the customer service window; templates are always allowed
 * (ADR-001).
 */
public sealed interface OutboundMessage {

    /** Customer address on the channel (WhatsApp: the {@code wa_id}). */
    String to();

    record Text(String to, String body) implements OutboundMessage {
        public Text {
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(body, "body");
        }
    }

    /**
     * Pre-approved template.
     *
     * @param bodyParameters values for the {@code {{n}}} placeholders of the template body, in order
     */
    record Template(String to, String name, String languageCode, List<String> bodyParameters)
            implements OutboundMessage {
        public Template {
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(languageCode, "languageCode");
            bodyParameters = bodyParameters == null ? List.of() : List.copyOf(bodyParameters);
        }
    }
}
