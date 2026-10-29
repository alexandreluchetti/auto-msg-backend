package br.com.conversa.app.persona;

import br.com.conversa.core.text.SearchText;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * The fixed texts the engine sends without the LLM (failure, refusal, "I am the virtual
 * assistant"), written per tone so they sound like the rest of the conversation (ADR-011). They stay
 * deterministic: the LLM never writes them.
 */
public final class PersonaTexts {

    private static final Map<String, String> FALLBACK = Map.of(
            "friendly", "Poxa, me enrolei aqui e não consegui te responder agora 😅 Pode mandar de novo daqui a "
                    + "pouquinho? A equipe também está de olho na conversa.",
            "casual", "Eita, travei aqui! Manda de novo daqui a pouco? A galera da equipe também tá acompanhando.",
            "formal", "Desculpe, não consegui responder agora. Poderia enviar sua mensagem novamente em instantes? "
                    + "A equipe também acompanha esta conversa.");
    private static final Map<String, String> REFUSAL = Map.of(
            "friendly", "Isso eu não consigo te ajudar, mas no cardápio, nos horários, na entrega ou no seu pedido "
                    + "é comigo mesmo! 😊",
            "casual", "Aí não é comigo! Mas cardápio, horário, entrega e pedido eu resolvo rapidinho.",
            "formal", "Desculpe, não posso ajudar com isso. Posso ajudar com o cardápio, horários, entrega ou "
                    + "formas de pagamento.");

    /**
     * An answer that claims or implies the assistant is a person ("sou humano", "não sou um robô").
     * The prompt forbids it; this catches what slips through.
     */
    private static final Pattern CLAIMS_HUMAN = Pattern.compile(
            "\\b(?:sou (?:um |uma )?(?:humano|humana|pessoa de verdade|pessoa real|gente de verdade)"
                    + "|nao sou (?:um |uma )?(?:robo|bot|maquina|assistente virtual|inteligencia artificial|ia))\\b");

    private PersonaTexts() {
    }

    public static String fallback(JsonNode config) {
        return FALLBACK.getOrDefault(tone(config), FALLBACK.get("friendly"));
    }

    public static String refusal(JsonNode config) {
        return REFUSAL.getOrDefault(tone(config), REFUSAL.get("friendly"));
    }

    /** What the assistant says when it must make clear it is not a person (D-012). */
    public static String virtualAssistant(JsonNode config) {
        String name = config.path("persona").path("name").asString("Assistente");
        String business = config.path("business").path("name").asString("restaurante");
        return switch (tone(config)) {
            case "formal" -> "Sou " + name + ", a assistente virtual do " + business
                    + ". Se preferir, chamo alguém da equipe para falar com você.";
            case "casual" -> "Sou a " + name + ", assistente virtual do " + business
                    + "! Se quiser, chamo alguém da equipe pra falar contigo.";
            default -> "Sou a " + name + ", assistente virtual do " + business
                    + " 😊 Se preferir, chamo alguém da equipe pra falar com você!";
        };
    }

    public static boolean claimsToBeHuman(String answer) {
        return CLAIMS_HUMAN.matcher(SearchText.normalize(answer)).find();
    }

    private static String tone(JsonNode config) {
        return config.path("persona").path("tone").asString("friendly");
    }
}
