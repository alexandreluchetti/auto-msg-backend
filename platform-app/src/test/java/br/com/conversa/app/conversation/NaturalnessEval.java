package br.com.conversa.app.conversation;

import br.com.conversa.app.conversation.ConversationRepository.StoredMessage;
import br.com.conversa.app.persona.PersonaPrompt;
import br.com.conversa.app.persona.PersonaTexts;
import br.com.conversa.app.tenant.TenantConfigResolver;
import br.com.conversa.core.business.BusinessDirectory;
import br.com.conversa.core.conversation.ConversationState;
import br.com.conversa.core.llm.LlmClient;
import br.com.conversa.core.llm.LlmResponse;
import br.com.conversa.core.tenant.TenantId;
import br.com.conversa.llm.anthropic.AnthropicLlmClient;
import br.com.conversa.llm.anthropic.AnthropicProperties;
import br.com.conversa.segment.restaurant.RestaurantSegmentPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H10 eval: multi-turn conversations of {@code eval/naturalness-conversations.json} answered by the
 * real engine, pack and <b>the real Claude API</b> (costs money), each answer checked by
 * {@link NaturalnessRules} and each transcript scored by {@link NaturalnessJudge} (also a real
 * model call). Writes {@code target/naturalness-report.md} with the score per criterion.
 *
 * <p>Never part of the build: run on purpose, with the user's approval and an API key,
 * {@code ANTHROPIC_API_KEY=... ./mvnw -pl platform-app -am test -Dtest=NaturalnessEval -Deval=true
 * -Dsurefire.failIfNoSpecifiedTests=false}; optional {@code -Deval.model}, {@code -Deval.effort},
 * {@code -Deval.judgeModel} and {@code -Deval.only=nat-01,nat-03}.
 *
 * <p>Acceptance: no rule violation, and an average of at least 4 for every criterion.
 */
@EnabledIfSystemProperty(named = "eval", matches = "true")
class NaturalnessEval {

    private static final TenantId TENANT = TenantId.of("00000000-0000-0000-0000-0000000000e2");
    private final JsonMapper mapper = JsonMapper.builder().build();

    record Result(String id, String title, String transcript, List<String> violations,
                  NaturalnessJudge.Verdict verdict, BigDecimal cost) {
    }

    @Test
    void conversationsReadLikeTheStaff() throws IOException {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        assertThat(apiKey).as("ANTHROPIC_API_KEY").isNotBlank();
        String model = System.getProperty("eval.model", "claude-opus-5-5");
        String effort = System.getProperty("eval.effort", "low");
        String judgeModel = System.getProperty("eval.judgeModel", "claude-opus-5-5");
        Set<String> only = new HashSet<>(List.of(System.getProperty("eval.only", "").split(",")));
        only.remove("");

        JsonNode fixture = read("eval/restaurant-fixture.json");
        ZoneId zone = ZoneId.of(fixture.path("timezone").asString());
        Clock clock = Clock.fixed(LocalDateTime.parse(fixture.path("now").asString()).atZone(zone).toInstant(), zone);
        BusinessDirectory directory = AssistantEval.directory(fixture, zone);
        String[] effectiveConfig = new String[1];
        Map<String, String> states = new HashMap<>();
        RestaurantSegmentPack pack = new RestaurantSegmentPack(directory, tenant -> effectiveConfig[0], new ConversationState() {
            @Override
            public String read(TenantId tenant, String conversationId) {
                return states.getOrDefault(conversationId, "{}");
            }

            @Override
            public void write(TenantId tenant, String conversationId, String stateJson) {
                states.put(conversationId, stateJson);
            }
        }, clock);

        LlmPricing pricing = AssistantEval.pricing();
        BigDecimal[] cost = {BigDecimal.ZERO};
        LlmClient claude = new AnthropicLlmClient(new AnthropicProperties(apiKey, model, null, Duration.ofSeconds(120), true));
        AssistantEngine engine = new AssistantEngine(claude,
                (tenant, conversation, requestedModel, requestedEffort, response, duration) ->
                        cost[0] = cost[0].add(pricing.cost(response.model(), response.usage())),
                new ToolInputValidator(mapper),
                new AssistantProperties(true, Duration.ZERO, effort, 4096, 4, 30, Duration.ofMinutes(2), 1, Duration.ZERO,
                        1, "claude-haiku-5-5"),
                mapper, clock);
        NaturalnessJudge judge = new NaturalnessJudge(new LlmClient() {
            @Override
            public boolean configured() {
                return true;
            }

            @Override
            public LlmResponse complete(br.com.conversa.core.llm.LlmRequest request) {
                LlmResponse response = claude.complete(request);
                cost[0] = cost[0].add(pricing.cost(response.model(), response.usage()));
                return response;
            }
        }, judgeModel);

        List<Result> results = new ArrayList<>();
        for (JsonNode conversation : read("eval/naturalness-conversations.json").path("conversations")) {
            String id = conversation.path("id").asString();
            if (!only.isEmpty() && !only.contains(id)) {
                continue;
            }
            ObjectNode own = (ObjectNode) fixture.path("config").deepCopy();
            if (conversation.has("persona")) {
                own.set("persona", conversation.path("persona"));
            }
            effectiveConfig[0] = new TenantConfigResolver(mapper).effectiveConfig(pack, own.toString()).toString();
            ObjectNode turnNode = (ObjectNode) mapper.readTree(effectiveConfig[0]);
            turnNode.putObject("llm").put("model", model).put("effort", effort);
            String turnConfig = turnNode.toString();
            JsonNode config = mapper.readTree(turnConfig);
            String prompt = PersonaPrompt.render(pack.defaultPromptTemplate(), config);
            String botName = config.path("persona").path("name").asString("Assistente");

            BigDecimal before = cost[0];
            UUID conversationId = UUID.randomUUID();
            List<StoredMessage> history = new ArrayList<>();
            StringBuilder transcript = new StringBuilder();
            List<String> violations = new ArrayList<>();
            for (JsonNode turn : conversation.path("turns")) {
                history.add(new StoredMessage("IN", "CUSTOMER", "wamid." + history.size(), turn.asString(), clock.instant()));
                transcript.append("Cliente: ").append(turn.asString()).append('\n');
                var answer = engine.answer(new AssistantEngine.Turn(TENANT, conversationId, pack, prompt, turnConfig, zone,
                        history, model, true, AssistantEngine.Memory.NONE));
                String text = switch (answer.outcome()) {
                    case HANDOFF -> PersonaTexts.handoff(config) + " [passou para a equipe]";
                    case CHECKOUT -> answer.checkout().summaryText() + " [botões: Confirmar pedido / Alterar]";
                    default -> answer.text();
                };
                if (answer.outcome() == AssistantEngine.Outcome.ANSWERED || answer.outcome() == AssistantEngine.Outcome.REFUSED) {
                    NaturalnessRules.violations(answer.text(), answer.options(), config)
                            .forEach(violation -> violations.add(turn.asString() + " → " + violation));
                }
                if (answer.outcome() == AssistantEngine.Outcome.FALLBACK) {
                    violations.add(turn.asString() + " → fallback answer");
                }
                String options = answer.options().isEmpty() ? "" : " [opções: " + String.join(" / ", answer.options()) + "]";
                history.add(new StoredMessage("OUT", "BOT", null, text, clock.instant()));
                transcript.append(botName).append(": ").append(text.replace("\n---\n", "\n")).append(options).append('\n');
                if (answer.outcome() == AssistantEngine.Outcome.HANDOFF) {
                    break;
                }
            }
            String persona = mapper.writeValueAsString(config.path("persona"));
            NaturalnessJudge.Verdict verdict = judge.judge(persona, transcript.toString());
            results.add(new Result(id, conversation.path("title").asString(), transcript.toString(), violations, verdict,
                    cost[0].subtract(before)));
            System.out.printf("%-8s %.1f %s%n", id, verdict.average(), violations.isEmpty() ? "" : violations);
        }

        Map<String, Double> averages = averages(results);
        Path report = Path.of("target", "naturalness-report.md");
        Files.writeString(report, report(results, averages, model, effort, judgeModel), StandardCharsets.UTF_8);
        System.out.println("Naturalness report: " + report.toAbsolutePath());
        assertThat(results.stream().flatMap(result -> result.violations().stream()).toList()).as("rule violations").isEmpty();
        averages.forEach((criterion, average) -> assertThat(average).as(criterion).isGreaterThanOrEqualTo(4.0));
    }

    static Map<String, Double> averages(List<Result> results) {
        Map<String, Double> averages = new LinkedHashMap<>();
        for (String criterion : NaturalnessJudge.CRITERIA) {
            averages.put(criterion, results.stream()
                    .mapToInt(result -> result.verdict().scores().get(criterion)).average().orElse(0));
        }
        return averages;
    }

    private static String report(List<Result> results, Map<String, Double> averages, String model, String effort,
                                 String judgeModel) {
        BigDecimal total = results.stream().map(Result::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
        StringBuilder out = new StringBuilder("# Naturalness eval\n\n");
        out.append("- Model: `").append(model).append("`, effort `").append(effort).append("`; judge `")
                .append(judgeModel).append("`\n");
        out.append("- Conversations: ").append(results.size()).append("; rule violations: ")
                .append(results.stream().mapToLong(result -> result.violations().size()).sum()).append('\n');
        out.append("- Cost: US$ ").append(total.setScale(4, RoundingMode.HALF_UP)).append(" (answers and judge)\n\n");
        out.append("| Criterion | Average (1–5) |\n|---|---|\n");
        averages.forEach((criterion, average) -> out.append("| ").append(criterion).append(" | ")
                .append(String.format(Locale.ROOT, "%.2f", average)).append(" |\n"));
        for (Result result : results) {
            out.append("\n## ").append(result.id()).append(" — ").append(result.title()).append("\n\n");
            out.append("```\n").append(result.transcript()).append("```\n\n");
            result.verdict().scores().forEach((criterion, score) -> out.append("- ").append(criterion).append(": ")
                    .append(score).append(" — ").append(result.verdict().comments().get(criterion)).append('\n'));
            if (!result.violations().isEmpty()) {
                out.append("- **Rule violations:** ").append(String.join("; ", result.violations())).append('\n');
            }
        }
        return out.toString();
    }

    private JsonNode read(String resource) throws IOException {
        try (var in = new ClassPathResource(resource).getInputStream()) {
            return mapper.readTree(in);
        }
    }
}
