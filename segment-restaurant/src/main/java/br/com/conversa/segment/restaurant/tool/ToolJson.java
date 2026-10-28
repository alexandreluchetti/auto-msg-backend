package br.com.conversa.segment.restaurant.tool;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.DayOfWeek;
import java.util.Locale;
import java.util.Map;

/**
 * JSON and pt-BR formatting shared by the restaurant tools. Amounts go to the LLM both as a number
 * and already formatted ("R$ 39,90"), so it never has to format prices itself.
 */
final class ToolJson {

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final Map<DayOfWeek, String> DAY_LABELS = Map.of(
            DayOfWeek.MONDAY, "segunda-feira",
            DayOfWeek.TUESDAY, "terça-feira",
            DayOfWeek.WEDNESDAY, "quarta-feira",
            DayOfWeek.THURSDAY, "quinta-feira",
            DayOfWeek.FRIDAY, "sexta-feira",
            DayOfWeek.SATURDAY, "sábado",
            DayOfWeek.SUNDAY, "domingo");

    private ToolJson() {
    }

    static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    /** Parses the tool input; a blank input is an empty object. */
    static JsonNode input(String inputJson) {
        if (inputJson == null || inputJson.isBlank()) {
            return object();
        }
        try {
            return MAPPER.readTree(inputJson);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Tool input is not valid JSON", e);
        }
    }

    /** Optional text field; blank counts as absent. */
    static String text(JsonNode input, String field) {
        JsonNode value = input.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString().trim() : null;
    }

    static void putMoney(ObjectNode node, String field, BigDecimal amount) {
        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_UP);
        node.put(field, scaled);
        node.put(field + "Formatted", formatMoney(scaled));
    }

    static String formatMoney(BigDecimal amount) {
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.of("pt", "BR")));
        return "R$ " + format.format(amount);
    }

    static String dayLabel(DayOfWeek day) {
        return DAY_LABELS.get(day);
    }

    static String json(JsonNode node) {
        return MAPPER.writeValueAsString(node);
    }
}
