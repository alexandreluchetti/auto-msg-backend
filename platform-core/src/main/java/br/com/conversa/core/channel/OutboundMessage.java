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

    /**
     * Text with up to 3 reply buttons (WhatsApp: interactive "button", free-form, so only inside the
     * customer service window). The customer's tap comes back with the button's {@code id}.
     */
    record Buttons(String to, String body, List<Button> buttons) implements OutboundMessage {
        public Buttons {
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(body, "body");
            buttons = List.copyOf(buttons);
            if (buttons.isEmpty() || buttons.size() > 3) {
                throw new IllegalArgumentException("1 to 3 buttons");
            }
        }
    }

    /**
     * A choice among up to 10 options (WhatsApp: interactive "list"), for when the customer has to
     * pick one of several (sizes, flavors). The pick comes back with the row's {@code id}.
     *
     * @param buttonLabel text of the button that opens the list, at most 20 characters
     */
    record Options(String to, String body, String buttonLabel, List<Button> rows) implements OutboundMessage {
        public Options {
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(body, "body");
            rows = List.copyOf(rows);
            if (rows.isEmpty() || rows.size() > 10) {
                throw new IllegalArgumentException("1 to 10 options");
            }
        }
    }

    /** @param title at most 20 characters (24 in a list row) */
    record Button(String id, String title) {
    }
}
