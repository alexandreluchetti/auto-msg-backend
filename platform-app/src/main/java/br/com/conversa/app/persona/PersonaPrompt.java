package br.com.conversa.app.persona;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a prompt template with a tenant's configuration (ADR-011): {@code {{persona}}} becomes
 * the persona block, {@code {{style}}} the delivery rules, {@code {{a.b}}} the text at that path of
 * the configuration. Pure: same template and configuration, same prompt, so the provider caches it.
 */
public final class PersonaPrompt {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z0-9_.]+)}}");

    private static final Map<String, String> TONES = Map.of(
            "friendly", "simpático e acolhedor",
            "formal", "cordial e respeitoso, sem gírias",
            "casual", "descontraído e próximo, como um amigo do bairro");
    private static final List<String> FORMALITY = List.of(
            "bem informal: pode usar \"vc\", \"tá\", \"beleza\", gírias leves",
            "informal: linguagem do dia a dia, sem gírias pesadas",
            "equilibrada: natural, sem gírias e sem formalidades",
            "mais formal: frases completas, trate por \"você\"",
            "formal: trate por \"senhor\"/\"senhora\" quando souber, sem abreviações");
    private static final Map<String, String> EMOJIS = Map.of(
            "none", "não use emojis",
            "low", "no máximo um emoji de vez em quando, nunca em toda mensagem",
            "moderate", "pode usar emojis com moderação, como a equipe faria (no máximo dois por mensagem)");

    private PersonaPrompt() {
    }

    /**
     * @throws IllegalArgumentException when the template names a value the configuration lacks
     */
    public static String render(String template, JsonNode config) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = switch (name) {
                case "persona" -> personaBlock(config);
                case "style" -> styleBlock(config);
                default -> text(config, name);
            };
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    static String personaBlock(JsonNode config) {
        JsonNode persona = config.path("persona");
        String business = config.path("business").path("name").asString("");
        List<String> lines = new ArrayList<>();
        lines.add("# Sua persona");
        lines.add("- Seu nome é " + persona.path("name").asString("Assistente") + ", a assistente virtual do " + business
                + ". Atenda como alguém da equipe atenderia.");
        lines.add("- Tom: " + TONES.getOrDefault(persona.path("tone").asString(), TONES.get("friendly")) + ".");
        int formality = Math.clamp(persona.path("formality").asInt(2), 1, 5);
        lines.add("- Formalidade: " + FORMALITY.get(formality - 1) + ".");
        lines.add("- Emojis: " + EMOJIS.getOrDefault(persona.path("emojis").asString(), EMOJIS.get("low")) + ".");
        List<String> expressions = strings(persona.path("expressions"));
        if (!expressions.isEmpty()) {
            lines.add("- Expressões que a equipe costuma usar (use de vez em quando, sem forçar): "
                    + String.join(", ", expressions.stream().map(expression -> "\"" + expression + "\"").toList()) + ".");
        }
        if (!persona.path("greeting").asString("").isBlank()) {
            lines.add("- Para cumprimentar no início da conversa, algo como: \"" + persona.path("greeting").asString() + "\".");
        }
        if (!persona.path("farewell").asString("").isBlank()) {
            lines.add("- Para se despedir, algo como: \"" + persona.path("farewell").asString() + "\".");
        }
        List<String> samples = strings(persona.path("sampleReplies"));
        if (!samples.isEmpty()) {
            lines.add("- Exemplos de como a equipe responde (copie o jeito de falar, nunca os fatos):");
            samples.forEach(sample -> lines.add("  - \"" + sample + "\""));
        }
        return String.join("\n", lines);
    }


    /** How answers are delivered (ADR-011): how many messages, and when to offer options. */
    static String styleBlock(JsonNode config) {
        int maxBubbles = Math.clamp(config.path("humanization").path("maxBubbles").asInt(2), 1, 2);
        String split = maxBubbles == 1
                ? "- Responda sempre numa mensagem só."
                : "- Se ficar mais natural, divida a resposta em duas mensagens, como as pessoas fazem no WhatsApp "
                        + "(por exemplo: a resposta e, depois, uma pergunta). Para isso, ponha uma linha só com --- "
                        + "entre as duas. Nunca mais de duas; na maioria das vezes, uma basta.";
        return split + "\n- Quando o cliente precisar escolher entre poucas opções reais de um item (tamanho, sabor, "
                + "borda) ou entre entrega e retirada, chame offer_options em vez de listar as opções no texto. "
                + "Para perguntas abertas (endereço, observações), apenas pergunte.";
    }

    private static String text(JsonNode config, String path) {
        JsonNode node = config;
        for (String part : path.split("\\.")) {
            node = node.path(part);
        }
        if (!node.isValueNode()) {
            throw new IllegalArgumentException("The prompt template uses {{" + path + "}}, missing in the configuration");
        }
        return node.asString();
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> {
            if (!value.asString("").isBlank()) {
                values.add(value.asString().strip());
            }
        });
        return values;
    }
}
