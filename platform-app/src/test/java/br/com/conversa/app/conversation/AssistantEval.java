package br.com.conversa.app.conversation;

import br.com.conversa.app.conversation.ConversationRepository.StoredMessage;
import br.com.conversa.app.tenant.TenantConfigResolver;
import br.com.conversa.core.business.BusinessDirectory;
import br.com.conversa.core.business.DeliveryZone;
import br.com.conversa.core.business.OpeningHours;
import br.com.conversa.core.business.OpeningPeriod;
import br.com.conversa.core.catalog.CatalogItem;
import br.com.conversa.core.catalog.CatalogOption;
import br.com.conversa.core.tenant.TenantId;
import br.com.conversa.core.text.SearchText;
import br.com.conversa.llm.anthropic.AnthropicLlmClient;
import br.com.conversa.llm.anthropic.AnthropicProperties;
import br.com.conversa.segment.restaurant.RestaurantSegmentPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
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
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5 eval: runs every question of {@code eval/restaurant-questions.json} through the real engine,
 * the real restaurant pack and <b>the real Claude API</b> (costs money), against the synthetic
 * restaurant of {@code eval/restaurant-fixture.json}, and writes {@code target/eval-report.md}.
 *
 * <p>Never part of the build: run on purpose with an API key,
 * {@code ANTHROPIC_API_KEY=... ./mvnw -pl platform-app -am test -Dtest=AssistantEval -Deval=true
 * -Dsurefire.failIfNoSpecifiedTests=false}; optional {@code -Deval.model=claude-sonnet-5-5},
 * {@code -Deval.effort=medium}, {@code -Deval.only=menu-01,hours-02}.
 *
 * <p>Grading is deterministic: the tools that must be called, case/accent-insensitive phrases the
 * answer must (or must not) contain, and that every "R$" amount in the answer is a real price of
 * the fixture (an item, an option, an item with one option, a fee or the minimum order).
 * Acceptance (ROADMAP M5): at least 95% of the questions pass and zero invented prices.
 */
@EnabledIfSystemProperty(named = "eval", matches = "true")
class AssistantEval {

    private static final Pattern AMOUNT = Pattern.compile("R\\$\\s?(\\d{1,3}(?:\\.\\d{3})*(?:,\\d{2})?)");
    private static final TenantId TENANT = TenantId.of("00000000-0000-0000-0000-0000000000e1");
    private final JsonMapper mapper = JsonMapper.builder().build();

    record Result(String id, String category, String question, String answer, List<String> tools, List<String> failures,
                  List<String> inventedPrices, BigDecimal cost) {
        boolean passed() {
            return failures.isEmpty() && inventedPrices.isEmpty();
        }
    }

    @Test
    void restaurantQuestions() throws IOException {
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        assertThat(apiKey).as("ANTHROPIC_API_KEY").isNotBlank();
        String model = System.getProperty("eval.model", "claude-opus-5-5");
        String effort = System.getProperty("eval.effort", "low");
        Set<String> only = new HashSet<>(List.of(System.getProperty("eval.only", "").split(",")));
        only.remove("");

        JsonNode fixture = read("eval/restaurant-fixture.json");
        ZoneId zone = ZoneId.of(fixture.path("timezone").asString());
        Clock clock = Clock.fixed(LocalDateTime.parse(fixture.path("now").asString()).atZone(zone).toInstant(), zone);
        BusinessDirectory directory = directory(fixture, zone);
        String[] effectiveConfig = new String[1];
        RestaurantSegmentPack pack = new RestaurantSegmentPack(directory, tenant -> effectiveConfig[0], clock);
        String ownConfig = mapper.writeValueAsString(fixture.path("config"));
        effectiveConfig[0] = new TenantConfigResolver(mapper).effectiveConfig(pack, ownConfig).toString();
        ObjectNode turnNode = (ObjectNode) mapper.readTree(effectiveConfig[0]);
        turnNode.putObject("llm").put("model", model).put("effort", effort);
        String turnConfig = turnNode.toString();

        LlmPricing pricing = pricing();
        BigDecimal[] cost = {BigDecimal.ZERO};
        AssistantEngine engine = new AssistantEngine(
                new AnthropicLlmClient(new AnthropicProperties(apiKey, model, null, Duration.ofSeconds(120), true)),
                (tenant, conversation, requestedModel, requestedEffort, response, duration) ->
                        cost[0] = cost[0].add(pricing.cost(response.model(), response.usage())),
                new ToolInputValidator(mapper),
                new AssistantProperties(true, Duration.ZERO, effort, 4096, 4, 30, Duration.ofMinutes(2), 1, Duration.ZERO),
                mapper, clock);

        Set<String> realPrices = realPrices(fixture);
        List<Result> results = new ArrayList<>();
        for (JsonNode question : read("eval/restaurant-questions.json").path("questions")) {
            String id = question.path("id").asString();
            if (!only.isEmpty() && !only.contains(id)) {
                continue;
            }
            List<StoredMessage> history = new ArrayList<>();
            question.path("history").forEach(turn -> history.add(new StoredMessage(
                    "customer".equals(turn.path("from").asString()) ? "IN" : "OUT",
                    "customer".equals(turn.path("from").asString()) ? "CUSTOMER" : "BOT",
                    null, turn.path("text").asString(), clock.instant())));
            history.add(new StoredMessage("IN", "CUSTOMER", "wamid.eval", question.path("question").asString(),
                    clock.instant()));
            BigDecimal before = cost[0];
            var answer = engine.answer(new AssistantEngine.Turn(TENANT, UUID.randomUUID(), pack, turnConfig, zone,
                    history, model, true));
            results.add(grade(question, answer, realPrices, cost[0].subtract(before)));
            System.out.printf("%-12s %s%n", id, results.getLast().passed() ? "PASS" : "FAIL " + results.getLast().failures());
        }

        Path report = Path.of("target", "eval-report.md");
        Files.writeString(report, report(results, model, effort), StandardCharsets.UTF_8);
        System.out.println("Eval report: " + report.toAbsolutePath());
        long passed = results.stream().filter(Result::passed).count();
        long invented = results.stream().mapToLong(result -> result.inventedPrices().size()).sum();
        assertThat(invented).as("invented prices").isZero();
        assertThat(passed * 100.0 / results.size()).as("pass rate (%)").isGreaterThanOrEqualTo(95.0);
    }

    private Result grade(JsonNode question, AssistantEngine.Answer answer, Set<String> realPrices, BigDecimal cost) {
        String text = answer.text();
        String normalized = SearchText.normalize(text);
        List<String> failures = new ArrayList<>();
        if (answer.outcome() == AssistantEngine.Outcome.FALLBACK) {
            failures.add("fallback answer");
        }
        for (JsonNode tool : question.path("tools")) {
            if (!answer.toolsCalled().contains(tool.asString())) {
                failures.add("did not call " + tool.asString());
            }
        }
        for (JsonNode group : question.path("contains")) {
            boolean matched = false;
            List<String> alternatives = new ArrayList<>();
            for (JsonNode alternative : group) {
                alternatives.add(alternative.asString());
                matched |= normalized.contains(SearchText.normalize(alternative.asString()));
            }
            if (!matched) {
                failures.add("missing one of " + alternatives);
            }
        }
        question.path("notContains").forEach(phrase -> {
            if (normalized.contains(SearchText.normalize(phrase.asString()))) {
                failures.add("contains '" + phrase.asString() + "'");
            }
        });
        question.path("notContainsRaw").forEach(phrase -> {
            if (text.contains(phrase.asString())) {
                failures.add("contains '" + phrase.asString() + "'");
            }
        });
        List<String> invented = new ArrayList<>();
        Matcher amounts = AMOUNT.matcher(text);
        while (amounts.find()) {
            String amount = amounts.group(1).contains(",") ? amounts.group(1) : amounts.group(1) + ",00";
            if (!realPrices.contains(amount)) {
                invented.add("R$ " + amounts.group(1));
            }
        }
        return new Result(question.path("id").asString(), question.path("category").asString(),
                question.path("question").asString(), text, answer.toolsCalled(), failures, invented, cost);
    }

    private static String report(List<Result> results, String model, String effort) {
        long passed = results.stream().filter(Result::passed).count();
        long invented = results.stream().mapToLong(result -> result.inventedPrices().size()).sum();
        BigDecimal total = results.stream().map(Result::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
        StringBuilder out = new StringBuilder("# Assistant eval\n\n");
        out.append("- Model: `").append(model).append("`, effort `").append(effort).append("`\n");
        out.append("- Passed: ").append(passed).append(" / ").append(results.size())
                .append(String.format(Locale.ROOT, " (%.1f%%)%n", passed * 100.0 / results.size()));
        out.append("- Invented prices: ").append(invented).append('\n');
        out.append("- Cost: US$ ").append(total.setScale(4, RoundingMode.HALF_UP)).append(" in total, US$ ")
                .append(total.divide(BigDecimal.valueOf(results.size()), 5, RoundingMode.HALF_UP))
                .append(" per question\n\n");
        out.append("| Id | Result | Tools | Answer |\n|---|---|---|---|\n");
        for (Result result : results) {
            String status = result.passed() ? "pass" : "**fail**: " + String.join("; ", result.failures())
                    + (result.inventedPrices().isEmpty() ? "" : " invented " + result.inventedPrices());
            out.append("| ").append(result.id()).append(" | ").append(status).append(" | ")
                    .append(String.join(", ", result.tools())).append(" | ")
                    .append(result.answer().replace("|", "\\|").replace("\n", "<br>")).append(" |\n");
        }
        return out.toString();
    }

    private Set<String> realPrices(JsonNode fixture) {
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.of("pt", "BR")));
        Set<String> prices = new HashSet<>();
        for (JsonNode item : fixture.path("menu")) {
            BigDecimal price = new BigDecimal(item.path("price").asString());
            prices.add(format.format(price));
            for (JsonNode option : fixture.path("options")) {
                if (option.path("item").asString().equals(item.path("code").asString())) {
                    BigDecimal delta = new BigDecimal(option.path("delta").asString());
                    prices.add(format.format(delta));
                    prices.add(format.format(price.add(delta)));
                }
            }
        }
        fixture.path("zones").forEach(zone -> prices.add(format.format(new BigDecimal(zone.path("fee").asString()))));
        prices.add(format.format(fixture.path("config").path("fulfillment").path("minimumOrder").decimalValue()));
        return prices;
    }

    private static BusinessDirectory directory(JsonNode fixture, ZoneId zone) {
        List<CatalogItem> catalog = new ArrayList<>();
        for (JsonNode item : fixture.path("menu")) {
            List<CatalogOption> options = new ArrayList<>();
            for (JsonNode option : fixture.path("options")) {
                if (option.path("item").asString().equals(item.path("code").asString())) {
                    options.add(new CatalogOption(option.path("group").asString(), option.path("name").asString(),
                            new BigDecimal(option.path("delta").asString())));
                }
            }
            catalog.add(new CatalogItem(UUID.randomUUID(), item.path("code").asString(), item.path("category").asString(),
                    item.path("name").asString(), item.path("description").asString(null),
                    new BigDecimal(item.path("price").asString()), item.path("available").asBoolean(true), options));
        }
        List<OpeningPeriod> periods = new ArrayList<>();
        fixture.path("hours").forEach(period -> periods.add(new OpeningPeriod(DayOfWeek.valueOf(period.path("day").asString()),
                LocalTime.parse(period.path("opens").asString()), LocalTime.parse(period.path("closes").asString()))));
        List<DeliveryZone> zones = new ArrayList<>();
        fixture.path("zones").forEach(entry -> zones.add(new DeliveryZone(entry.path("neighborhood").asString(),
                new BigDecimal(entry.path("fee").asString()))));
        return new BusinessDirectory() {
            @Override
            public List<CatalogItem> catalog(TenantId tenant) {
                return catalog;
            }

            @Override
            public OpeningHours openingHours(TenantId tenant) {
                return new OpeningHours(zone, periods);
            }

            @Override
            public List<DeliveryZone> deliveryZones(TenantId tenant) {
                return zones;
            }
        };
    }

    private static LlmPricing pricing() throws IOException {
        var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        return new Binder(ConfigurationPropertySources.from(sources)).bind("conversa.llm", LlmPricing.class).get();
    }

    private JsonNode read(String resource) throws IOException {
        try (var in = new ClassPathResource(resource).getInputStream()) {
            return mapper.readTree(in);
        }
    }
}
