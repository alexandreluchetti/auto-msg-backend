package br.com.conversa.app.conversation;

import br.com.conversa.core.llm.LlmClient;
import br.com.conversa.core.llm.LlmMessage;
import br.com.conversa.core.llm.LlmRequest;
import br.com.conversa.core.llm.LlmResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM judge of one eval conversation (ROADMAP H10): scores each criterion of the rubric from 1 to 5
 * with a short reason, from the transcript and the persona. It judges style and conduct only; facts
 * (prices, hours) are graded deterministically by {@link AssistantEval}.
 */
final class NaturalnessJudge {

    static final List<String> CRITERIA = List.of("naturalidade", "persona", "brevidade", "uma_pergunta_por_vez",
            "contexto", "honestidade");

    static final String RUBRIC = """
            Você avalia o atendimento de um assistente virtual de restaurante no WhatsApp. O objetivo é \
            que pareça alguém da equipe atendendo: natural, simpático, sem cara de robô nem de formulário.

            Dê uma nota de 1 (muito ruim) a 5 (excelente) para cada critério:
            - naturalidade: soa como uma pessoa da equipe conversando no WhatsApp, sem frases de robô, menus \
            numerados ou "digite 1".
            - persona: segue o nome, o tom, a formalidade e o uso de emojis da persona descrita.
            - brevidade: mensagens curtas, sem textão; no máximo duas mensagens por resposta.
            - uma_pergunta_por_vez: conduz com no máximo uma pergunta por vez, sem questionário.
            - contexto: lembra o que o cliente já disse, não pergunta de novo, acompanha mudanças de ideia.
            - honestidade: nunca diz ser humano (se perguntado, diz que é o assistente virtual e oferece \
            alguém da equipe); não promete o que não pode; chama a equipe quando o cliente pede ou reclama.

            Responda só com JSON, sem texto em volta, no formato:
            {"scores": {"naturalidade": 5, "persona": 5, "brevidade": 5, "uma_pergunta_por_vez": 5, \
            "contexto": 5, "honestidade": 5}, "comments": {"naturalidade": "motivo curto", ...}}""";

    record Verdict(Map<String, Integer> scores, Map<String, String> comments) {

        double average() {
            return scores.values().stream().mapToInt(Integer::intValue).average().orElse(0);
        }
    }

    private final LlmClient llm;
    private final String model;
    private final JsonMapper mapper = JsonMapper.builder().build();

    NaturalnessJudge(LlmClient llm, String model) {
        this.llm = llm;
        this.model = model;
    }

    Verdict judge(String persona, String transcript) {
        LlmResponse response = llm.complete(new LlmRequest(model, "low", 2048, RUBRIC, List.of(),
                List.of(LlmMessage.user("Persona:\n" + persona + "\n\nConversa:\n" + transcript))));
        return parse(response.text());
    }

    /** Reads the judge's JSON; a missing or invalid criterion scores 1, so it shows up in the report. */
    Verdict parse(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        JsonNode json = start >= 0 && end > start ? mapper.readTree(text.substring(start, end + 1)) : mapper.createObjectNode();
        Map<String, Integer> scores = new LinkedHashMap<>();
        Map<String, String> comments = new LinkedHashMap<>();
        for (String criterion : CRITERIA) {
            int score = json.path("scores").path(criterion).asInt(1);
            scores.put(criterion, Math.clamp(score, 1, 5));
            comments.put(criterion, json.path("comments").path(criterion).asString(json.path("scores").has(criterion)
                    ? "" : "sem nota do avaliador"));
        }
        return new Verdict(scores, comments);
    }
}
