package br.com.conversa.app.compose;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Decides how an answer is delivered (ADR-011), separately from how it is generated: at most
 * {@code humanization.maxBubbles} WhatsApp messages (D-014), split where the assistant marked it
 * ({@code ---} on its own line) or, without a mark, before a closing question of a long answer;
 * and a choice the assistant offered ({@code offer_options}) as buttons (up to 3) or a list (up to
 * 10). Pure and deterministic.
 */
public final class ResponseComposer {

    /** One WhatsApp message of the answer. */
    public sealed interface Bubble {
        String text();
    }

    public record TextBubble(String text) implements Bubble {
    }

    /** A question with options: buttons when there are at most 3, a list otherwise. */
    public record ChoiceBubble(String text, List<Choice> choices) implements Bubble {

        public ChoiceBubble {
            choices = List.copyOf(choices);
        }

        public boolean asButtons() {
            return choices.size() <= MAX_BUTTONS;
        }
    }

    /** @param id what comes back when the customer taps it */
    public record Choice(String id, String title) {
    }

    static final int MAX_BUTTONS = 3;
    static final int MAX_LIST_ROWS = 10;
    private static final int BUTTON_TITLE = 20;
    private static final int ROW_TITLE = 24;
    private static final int SPLIT_MIN_LENGTH = 140;
    private static final Pattern MARK = Pattern.compile("(?m)^\\s*-{3,}\\s*$");
    private static final Pattern PARAGRAPHS = Pattern.compile("\\n\\s*\\n");

    private ResponseComposer() {
    }

    /**
     * @param options choices the assistant offered with {@code offer_options}; empty for none
     */
    public static List<Bubble> compose(String answer, List<String> options, JsonNode config) {
        int maxBubbles = Math.clamp(config.path("humanization").path("maxBubbles").asInt(2), 1, 2);
        List<String> parts = split(answer == null ? "" : answer.strip(), maxBubbles);
        List<Bubble> bubbles = new ArrayList<>();
        parts.forEach(part -> bubbles.add(new TextBubble(part)));
        if (!options.isEmpty()) {
            String question = bubbles.isEmpty() ? "Qual você prefere?" : bubbles.removeLast().text();
            bubbles.add(new ChoiceBubble(question, choices(options)));
        }
        return bubbles;
    }

    static List<String> split(String text, int maxBubbles) {
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>(Arrays.stream(MARK.split(text)).map(String::strip)
                .filter(part -> !part.isEmpty()).toList());
        if (parts.size() == 1 && maxBubbles > 1) {
            parts = splitBeforeClosingQuestion(parts.getFirst());
        }
        if (parts.size() > maxBubbles) {
            List<String> merged = new ArrayList<>(parts.subList(0, maxBubbles - 1));
            merged.add(String.join("\n\n", parts.subList(maxBubbles - 1, parts.size())));
            parts = merged;
        }
        return parts;
    }

    /** "Long answer.\n\nAnd a question?" reads better as two messages, the way people write. */
    private static List<String> splitBeforeClosingQuestion(String text) {
        String[] paragraphs = PARAGRAPHS.split(text);
        String last = paragraphs[paragraphs.length - 1].strip();
        if (paragraphs.length < 2 || text.length() < SPLIT_MIN_LENGTH || !last.endsWith("?")) {
            return List.of(text);
        }
        String head = String.join("\n\n", Arrays.copyOf(paragraphs, paragraphs.length - 1)).strip();
        return List.of(head, last);
    }

    private static List<Choice> choices(List<String> options) {
        List<String> limited = options.subList(0, Math.min(options.size(), MAX_LIST_ROWS));
        int max = limited.size() <= MAX_BUTTONS ? BUTTON_TITLE : ROW_TITLE;
        List<Choice> choices = new ArrayList<>();
        for (int index = 0; index < limited.size(); index++) {
            String title = limited.get(index).strip();
            if (title.length() > max) {
                title = title.substring(0, max - 1) + "…";
            }
            choices.add(new Choice("option:" + (index + 1), title));
        }
        return choices;
    }
}
