package br.com.conversa.app.conversation;

/** Why a conversation went to staff. Labels are shown to staff (pt-BR). */
public enum HandoffReason {

    STAFF_REPLY("a equipe respondeu pelo app"),
    CUSTOMER_REQUEST("o cliente pediu para falar com um atendente"),
    BOT_REQUEST("o assistente pediu ajuda"),
    REPEATED_FAILURES("o assistente não conseguiu responder");

    private final String label;

    HandoffReason(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
