package br.com.conversa.app.conversation;

import br.com.conversa.app.compose.ResponseComposer;
import br.com.conversa.app.persona.PersonaTexts;
import br.com.conversa.core.text.SearchText;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Deterministic checks of the humanized style (ADR-011, D-012, D-014) on one composed answer: the
 * hard rules the LLM judge does not need to weigh. Used by {@link NaturalnessEval}.
 */
final class NaturalnessRules {

    static final int MAX_BUBBLE_LENGTH = 700;
    private static final Pattern MENU_NUMBERS = Pattern.compile("(?m)^\\s*\\d+\\s*[-).]\\s+\\S");
    private static final Pattern MARKDOWN = Pattern.compile("(?m)^\\s*#{1,6}\\s|\\|\\s*-{3,}|\\*\\*[^*]+\\*\\*");
    private static final List<String> ROBOTIC = List.of("digite", "prezado cliente", "estou aqui para ajudar",
            "como um modelo de linguagem", "sou uma ia", "sou uma inteligencia artificial", "opcao invalida",
            "selecione uma das opcoes");

    private NaturalnessRules() {
    }

    /** @return what the answer breaks, empty when it reads like the staff would write */
    static List<String> violations(String answer, List<String> options, JsonNode config) {
        List<String> found = new ArrayList<>();
        List<ResponseComposer.Bubble> bubbles = ResponseComposer.compose(answer, options, config);
        int maxBubbles = config.path("humanization").path("maxBubbles").asInt(2);
        if (bubbles.size() > maxBubbles) {
            found.add("more than " + maxBubbles + " messages");
        }
        for (ResponseComposer.Bubble bubble : bubbles) {
            String text = bubble.text();
            if (text.length() > MAX_BUBBLE_LENGTH) {
                found.add("message longer than " + MAX_BUBBLE_LENGTH + " characters");
            }
            if (MENU_NUMBERS.matcher(text).find()) {
                found.add("numbered menu of options");
            }
            if (MARKDOWN.matcher(text).find()) {
                found.add("markdown (headings, tables or **bold**)");
            }
            String normalized = SearchText.normalize(text);
            ROBOTIC.stream().filter(normalized::contains).forEach(phrase -> found.add("robotic phrase '" + phrase + "'"));
            if (PersonaTexts.claimsToBeHuman(text)) {
                found.add("claims to be a person");
            }
        }
        return found;
    }
}
