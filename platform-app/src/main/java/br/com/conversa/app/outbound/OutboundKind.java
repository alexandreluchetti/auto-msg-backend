package br.com.conversa.app.outbound;

public enum OutboundKind {
    TEXT, TEMPLATE, BUTTONS, READ;

    /** Free-form messages: only inside the customer service window. */
    boolean freeForm() {
        return this == TEXT || this == BUTTONS;
    }
}
