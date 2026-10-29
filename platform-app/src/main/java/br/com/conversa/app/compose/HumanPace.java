package br.com.conversa.app.compose;

import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * When each message of a turn goes out (ADR-011): "typing..." right away, then each bubble after a
 * delay proportional to its length, between the business's minimum and maximum, with the typing
 * indicator shown again before the next bubble. Pure: the outbox does the waiting.
 */
public final class HumanPace {

    /** Gap between a bubble and the typing indicator for the next one. */
    static final Duration RETYPE_AFTER = Duration.ofMillis(300);

    public enum Step { TYPING, BUBBLE }

    /** @param bubble index of the bubble for {@link Step#BUBBLE}, -1 for typing */
    public record Scheduled(Step step, int bubble, Instant at) {
    }

    private HumanPace() {
    }

    /** @param scale multiplies every delay (1 in production; 0 in tests that send at once) */
    public static List<Scheduled> plan(List<String> bubbleTexts, JsonNode config, Instant now, double scale) {
        JsonNode pace = config.path("humanization");
        boolean typing = pace.path("typingIndicator").asBoolean(true);
        List<Scheduled> plan = new ArrayList<>();
        Instant at = now;
        if (typing) {
            plan.add(new Scheduled(Step.TYPING, -1, at));
        }
        for (int index = 0; index < bubbleTexts.size(); index++) {
            if (index > 0 && typing) {
                plan.add(new Scheduled(Step.TYPING, -1, at.plus(scaled(RETYPE_AFTER, scale))));
            }
            at = at.plus(scaled(delay(bubbleTexts.get(index), pace), scale));
            plan.add(new Scheduled(Step.BUBBLE, index, at));
        }
        return plan;
    }

    private static Duration scaled(Duration duration, double scale) {
        return Duration.ofMillis(Math.round(duration.toMillis() * scale));
    }

    static Duration delay(String text, JsonNode pace) {
        long perChar = pace.path("msPerChar").asLong(40);
        long min = pace.path("minDelayMs").asLong(1200);
        long max = Math.max(min, pace.path("maxDelayMs").asLong(6000));
        return Duration.ofMillis(Math.clamp(perChar * text.length(), min, max));
    }
}
