package br.com.conversa.app.persona;

import br.com.conversa.app.tenant.TenantConfigResolver;
import br.com.conversa.segment.restaurant.RestaurantSegmentPack;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersonaPromptTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final RestaurantSegmentPack restaurant = new RestaurantSegmentPack(null, null, null, Clock.systemUTC());

    @Test
    void rendersThePersonaOfTheBusiness() {
        JsonNode config = mapper.readTree("""
                {"business": {"name": "Cantina da Praça"},
                 "persona": {"name": "Rita", "tone": "casual", "formality": 1, "emojis": "none",
                             "expressions": ["Opa!", "Show!"], "greeting": "Fala, tudo certo?",
                             "farewell": "Valeu, até a próxima!",
                             "sampleReplies": ["Tem sim! A calabresa é a mais pedida aqui."]}}
                """);

        String block = PersonaPrompt.personaBlock(config);

        assertThat(block).contains(
                "Seu nome é Rita, a assistente virtual do Cantina da Praça.",
                "Tom: descontraído e próximo",
                "Formalidade: bem informal",
                "Emojis: não use emojis.",
                "\"Opa!\", \"Show!\"",
                "\"Fala, tudo certo?\"",
                "\"Valeu, até a próxima!\"",
                "copie o jeito de falar, nunca os fatos",
                "\"Tem sim! A calabresa é a mais pedida aqui.\"");
    }

    @Test
    void leavesOutWhatThePersonaDoesNotSet() {
        String block = PersonaPrompt.personaBlock(mapper.readTree("""
                {"business": {"name": "Cantina"}, "persona": {"name": "Rita", "tone": "formal", "formality": 5,
                 "emojis": "moderate"}}
                """));

        assertThat(block).contains("Tom: cordial", "Formalidade: formal", "Emojis: pode usar emojis com moderação")
                .doesNotContain("Expressões", "cumprimentar", "despedir", "Exemplos");
    }

    @Test
    void theRestaurantTemplateRendersWithThePackDefaults() {
        JsonNode config = new TenantConfigResolver(mapper)
                .effectiveConfig(restaurant, "{\"business\": {\"name\": \"Cantina da Praça\"}}");

        String prompt = PersonaPrompt.render(restaurant.defaultPromptTemplate(), config);

        assertThat(prompt).doesNotContain("{{")
                .contains("restaurante Cantina da Praça", "# Sua persona", "Seu nome é Assistente",
                        "Nunca use \"digite 1\"",
                        "é a assistente virtual do Cantina da Praça e ofereça chamar alguém da equipe");
    }

    @Test
    void aTemplateNamingAMissingValueFails() {
        assertThatThrownBy(() -> PersonaPrompt.render("Olá {{business.slogan}}", mapper.readTree("{\"business\": {}}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("{{business.slogan}}");
    }
}
