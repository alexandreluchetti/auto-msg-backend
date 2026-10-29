package br.com.conversa.app.persona;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class PersonaTextsTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void catchesAnswersThatClaimToBeAPerson() {
        assertThat(PersonaTexts.claimsToBeHuman("Sou humana sim, pode confiar!")).isTrue();
        assertThat(PersonaTexts.claimsToBeHuman("Não, não sou um robô 😄")).isTrue();
        assertThat(PersonaTexts.claimsToBeHuman("Sou uma pessoa de verdade aqui da cozinha")).isTrue();
        assertThat(PersonaTexts.claimsToBeHuman("Não sou IA, sou a Rita")).isTrue();
    }

    @Test
    void letsOrdinaryAnswersThrough() {
        assertThat(PersonaTexts.claimsToBeHuman("Sou a Rita, assistente virtual da Cantina!")).isFalse();
        assertThat(PersonaTexts.claimsToBeHuman("A pizza serve bem uma pessoa")).isFalse();
        assertThat(PersonaTexts.claimsToBeHuman("Tem sim! Quer que eu anote?")).isFalse();
    }

    @Test
    void fixedTextsFollowTheTone() {
        var casual = mapper.readTree("""
                {"business": {"name": "Cantina"}, "persona": {"name": "Rita", "tone": "casual"}}""");
        var formal = mapper.readTree("""
                {"business": {"name": "Cantina"}, "persona": {"name": "Rita", "tone": "formal"}}""");

        assertThat(PersonaTexts.fallback(casual)).isNotEqualTo(PersonaTexts.fallback(formal));
        assertThat(PersonaTexts.refusal(formal)).startsWith("Desculpe");
        assertThat(PersonaTexts.virtualAssistant(casual)).contains("Rita", "assistente virtual do Cantina", "equipe");
        assertThat(PersonaTexts.claimsToBeHuman(PersonaTexts.virtualAssistant(formal))).isFalse();
    }
}
