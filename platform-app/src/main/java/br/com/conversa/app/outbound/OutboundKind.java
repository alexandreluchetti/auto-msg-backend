package br.com.conversa.app.outbound;

public enum OutboundKind {
    TEXT, TEMPLATE, BUTTONS, LIST, READ, TYPING;

    /** Free-form messages: only inside the customer service window. */
    boolean freeForm() {
        return this == TEXT || this == BUTTONS || this == LIST;
    }

    /** Status updates on a received message (read receipt, typing indicator), not messages. */
    boolean statusOnly() {
        return this == READ || this == TYPING;
    }
}
