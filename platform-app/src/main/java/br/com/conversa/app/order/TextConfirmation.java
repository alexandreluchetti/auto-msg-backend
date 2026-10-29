package br.com.conversa.app.order;

import br.com.conversa.core.text.SearchText;

import java.util.regex.Pattern;

/**
 * The fixed rule that recognizes a clear confirmation in text right after the order summary
 * (D-016): the whole message must be an agreement ("pode mandar", "confirmo", "fechado", "sim,
 * pode confirmar"), optionally with "por favor" or thanks. Anything else, such as "sim, mas sem
 * cebola", "acho que sim" or a question, is not a confirmation and goes to the assistant, which asks again.
 * The LLM never confirms.
 */
public final class TextConfirmation {

    private static final String AGREE = "(?:sim|isso|isso mesmo|ok|okay|beleza|blz|perfeito|certo|tudo certo|ta certo"
            + "|esta certo|ta tudo certo|esta tudo certo|correto|exato|tudo ok|ta otimo|otimo|show)";
    private static final String CONFIRM = "(?:(?:pode )?(?:mandar|manda|enviar|envia|confirmar|confirma|fechar|fecha"
            + "|fazer|faz|preparar|prepara|seguir|segue)(?: o pedido| pedido)?(?: sim)?"
            + "|confirmo|confirmado|confirmada|confirmar pedido|fechado|fechou|pode ser|pode sim|bora|manda ver"
            + "|manda bala|pode|" + AGREE + ")";
    private static final String COURTESY = "(?: (?:por favor|pfv|pf|obrigado|obrigada|obg|valeu|vlw))*";
    private static final Pattern CLEAR = Pattern.compile(
            "^(?:" + AGREE + " )*" + CONFIRM + COURTESY + "$");

    private TextConfirmation() {
    }

    /** Whether the whole message clearly confirms the order just summarized. */
    public static boolean isClear(String text) {
        String normalized = SearchText.normalize(text);
        return !normalized.isEmpty() && normalized.length() <= 60 && !text.contains("?")
                && CLEAR.matcher(normalized).matches();
    }
}
