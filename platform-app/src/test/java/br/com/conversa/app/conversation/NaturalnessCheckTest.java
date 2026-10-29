package br.com.conversa.app.conversation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The offline parts of the H10 eval: the style rules and reading the judge's verdict. */
class NaturalnessCheckTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final JsonNode config = mapper.readTree("{\"humanization\": {\"maxBubbles\": 2}}");

    @Test
    void aNaturalAnswerBreaksNoRule() {
        assertThat(NaturalnessRules.violations("Temos sim! A calabresa sai R$ 49,90 😊\n---\nQuer grande ou média?",
                List.of(), config)).isEmpty();
    }

    @Test
    void roboticAnswersAreCaught() {
        assertThat(NaturalnessRules.violations("Prezado cliente, digite uma opção:\n1 - Pizzas\n2 - Lanches", List.of(),
                config)).contains("numbered menu of options", "robotic phrase 'digite'", "robotic phrase 'prezado cliente'");
        assertThat(NaturalnessRules.violations("## Cardápio\n**Pizzas**", List.of(), config))
                .contains("markdown (headings, tables or **bold**)");
        assertThat(NaturalnessRules.violations("Pode ficar tranquilo, sou humana sim!", List.of(), config))
                .contains("claims to be a person");
        assertThat(NaturalnessRules.violations("a".repeat(800), List.of(), config))
                .contains("message longer than 700 characters");
    }

    @Test
    void readsTheJudgesScores() {
        NaturalnessJudge judge = new NaturalnessJudge(new ScriptedLlm(), "claude-opus-5-5");

        NaturalnessJudge.Verdict verdict = judge.parse("""
                Aqui está: {"scores": {"naturalidade": 5, "persona": 4, "brevidade": 9, "uma_pergunta_por_vez": 5,
                "contexto": 4}, "comments": {"persona": "poucos emojis"}}""");

        assertThat(verdict.scores()).containsEntry("naturalidade", 5).containsEntry("brevidade", 5)
                .containsEntry("honestidade", 1);
        assertThat(verdict.comments()).containsEntry("persona", "poucos emojis")
                .containsEntry("honestidade", "sem nota do avaliador");
        assertThat(judge.parse("não sei").average()).isEqualTo(1.0);
    }

    @Test
    void theJudgeGetsTheRubricPersonaAndTranscript() {
        ScriptedLlm llm = new ScriptedLlm();
        llm.then(ScriptedLlm.text("{\"scores\": {\"naturalidade\": 4, \"persona\": 4, \"brevidade\": 4, "
                + "\"uma_pergunta_por_vez\": 4, \"contexto\": 4, \"honestidade\": 4}}"));

        NaturalnessJudge.Verdict verdict = new NaturalnessJudge(llm, "claude-opus-5-5")
                .judge("{\"name\": \"Rita\"}", "Cliente: Oi\nRita: Oi! Tudo bem?\n");

        assertThat(verdict.average()).isEqualTo(4.0);
        assertThat(llm.requests().getFirst().system()).isEqualTo(NaturalnessJudge.RUBRIC);
        assertThat(llm.requests().getFirst().model()).isEqualTo("claude-opus-5-5");
    }
}
