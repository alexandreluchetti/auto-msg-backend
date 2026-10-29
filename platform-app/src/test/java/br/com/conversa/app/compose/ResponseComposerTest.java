package br.com.conversa.app.compose;

import br.com.conversa.app.compose.ResponseComposer.ChoiceBubble;
import br.com.conversa.app.compose.ResponseComposer.TextBubble;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseComposerTest {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final JsonNode twoBubbles = mapper.readTree("""
            {"humanization": {"maxBubbles": 2, "typingIndicator": true, "msPerChar": 40, "minDelayMs": 1200,
                              "maxDelayMs": 6000}}""");
    private final JsonNode oneBubble = mapper.readTree("{\"humanization\": {\"maxBubbles\": 1}}");

    @Test
    void splitsWhereTheAssistantMarked() {
        var bubbles = ResponseComposer.compose("Tem sim! A calabresa sai por R$ 49,90.\n---\nQuer grande ou média?",
                List.of(), twoBubbles);

        assertThat(bubbles).containsExactly(new TextBubble("Tem sim! A calabresa sai por R$ 49,90."),
                new TextBubble("Quer grande ou média?"));
    }

    @Test
    void neverSendsMoreThanTheLimit() {
        var three = ResponseComposer.compose("Um\n---\nDois\n---\nTrês?", List.of(), twoBubbles);
        var single = ResponseComposer.compose("Tem sim!\n---\nQuer grande?", List.of(), oneBubble);

        assertThat(three).extracting(ResponseComposer.Bubble::text).containsExactly("Um", "Dois\n\nTrês?");
        assertThat(single).extracting(ResponseComposer.Bubble::text).containsExactly("Tem sim!\n\nQuer grande?");
    }

    @Test
    void aLongAnswerEndingInAQuestionBecomesTwoMessages() {
        String answer = "Temos calabresa, margherita, portuguesa e frango com catupiry, todas em média e grande, "
                + "e a borda de catupiry sai por mais R$ 8,00.\n\nQual vai ser hoje?";

        assertThat(ResponseComposer.compose(answer, List.of(), twoBubbles)).extracting(ResponseComposer.Bubble::text)
                .containsExactly("Temos calabresa, margherita, portuguesa e frango com catupiry, todas em média e "
                        + "grande, e a borda de catupiry sai por mais R$ 8,00.", "Qual vai ser hoje?");
        assertThat(ResponseComposer.compose("Oi!\n\nTudo bem?", List.of(), twoBubbles)).hasSize(1);
        assertThat(ResponseComposer.compose(answer.replace("?", "."), List.of(), twoBubbles)).hasSize(1);
    }

    @Test
    void offeredOptionsBecomeButtonsOrAList() {
        var buttons = ResponseComposer.compose("Anotei a calabresa!\n---\nQual tamanho?", List.of("Média", "Grande"),
                twoBubbles);
        var list = ResponseComposer.compose("Qual sabor?", List.of("Calabresa", "Margherita", "Portuguesa",
                "Frango com Catupiry e Bacon Extra"), twoBubbles);

        assertThat(buttons.getFirst()).isEqualTo(new TextBubble("Anotei a calabresa!"));
        assertThat(buttons.getLast()).isInstanceOfSatisfying(ChoiceBubble.class, choice -> {
            assertThat(choice.text()).isEqualTo("Qual tamanho?");
            assertThat(choice.asButtons()).isTrue();
            assertThat(choice.choices()).extracting(ResponseComposer.Choice::title).containsExactly("Média", "Grande");
        });
        assertThat(list).singleElement().isInstanceOfSatisfying(ChoiceBubble.class, choice -> {
            assertThat(choice.asButtons()).isFalse();
            assertThat(choice.choices().getLast().title()).hasSize(24).endsWith("…");
        });
    }

    @Test
    void pacesBubblesByLengthWithinTheLimits() {
        Instant now = Instant.parse("2026-10-29T20:00:00Z");
        String shortText = "Tem sim!";
        String longText = "x".repeat(400);

        var plan = HumanPace.plan(List.of(shortText, longText), twoBubbles, now, 1);

        assertThat(plan).extracting(HumanPace.Scheduled::step).containsExactly(
                HumanPace.Step.TYPING, HumanPace.Step.BUBBLE, HumanPace.Step.TYPING, HumanPace.Step.BUBBLE);
        assertThat(plan.get(0).at()).isEqualTo(now);
        assertThat(plan.get(1).at()).isEqualTo(now.plus(Duration.ofMillis(1200)));
        assertThat(plan.get(2).at()).isEqualTo(now.plus(Duration.ofMillis(1500)));
        assertThat(plan.get(3).at()).isEqualTo(now.plus(Duration.ofMillis(1200 + 6000)));
        assertThat(HumanPace.plan(List.of(longText), twoBubbles, now, 0)).allSatisfy(step ->
                assertThat(step.at()).isEqualTo(now));
        JsonNode noTyping = mapper.readTree("{\"humanization\": {\"typingIndicator\": false}}");
        assertThat(HumanPace.plan(List.of(shortText), noTyping,
                now, 1)).extracting(HumanPace.Scheduled::step).containsExactly(HumanPace.Step.BUBBLE);
    }
}
