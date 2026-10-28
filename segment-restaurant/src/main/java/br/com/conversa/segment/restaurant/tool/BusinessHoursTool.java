package br.com.conversa.segment.restaurant.tool;

import br.com.conversa.core.business.BusinessDirectory;
import br.com.conversa.core.business.OpeningHours;
import br.com.conversa.core.business.OpeningPeriod;
import br.com.conversa.core.tool.Tool;
import br.com.conversa.core.tool.ToolInvocation;
import br.com.conversa.core.tool.ToolResult;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Comparator;

/**
 * Opening hours, whether the restaurant is open now and when it opens next.
 */
public final class BusinessHoursTool implements Tool {

    private final BusinessDirectory directory;
    private final Clock clock;

    public BusinessHoursTool(BusinessDirectory directory, Clock clock) {
        this.directory = directory;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "get_business_hours";
    }

    @Override
    public String description() {
        return """
                Horário de funcionamento do restaurante: a semana inteira, se está aberto agora, \
                até que horas fica aberto e quando abre de novo. Use para qualquer pergunta sobre \
                horário ou se dá para pedir agora. Dias sem períodos estão fechados. Se \
                "hoursRegistered" for false, o restaurante não cadastrou o horário: não invente.""";
    }

    @Override
    public String inputSchema() {
        return """
                {"type": "object", "additionalProperties": false, "properties": {}}""";
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        OpeningHours hours = directory.openingHours(invocation.tenantId());
        ObjectNode result = ToolJson.object();
        if (!hours.registered()) {
            result.put("hoursRegistered", false);
            return ToolResult.ok(ToolJson.json(result));
        }
        Instant now = clock.instant();
        ZonedDateTime local = now.atZone(hours.zone());
        result.put("hoursRegistered", true);
        result.put("timezone", hours.zone().getId());
        result.putObject("now")
                .put("date", local.toLocalDate().toString())
                .put("time", hhmm(local))
                .put("day", ToolJson.dayLabel(local.getDayOfWeek()));

        var current = hours.current(now);
        result.put("openNow", current.isPresent());
        current.ifPresent(interval -> result.put("closesAt", hhmm(interval.closes())));
        hours.next(now).ifPresent(interval -> result.putObject("nextOpening")
                .put("date", interval.opens().toLocalDate().toString())
                .put("day", ToolJson.dayLabel(interval.opens().getDayOfWeek()))
                .put("time", hhmm(interval.opens())));

        ArrayNode week = result.putArray("week");
        for (DayOfWeek day : DayOfWeek.values()) {
            ArrayNode periods = week.addObject().put("day", ToolJson.dayLabel(day)).putArray("periods");
            hours.periods().stream()
                    .filter(period -> period.day() == day)
                    .sorted(Comparator.comparing(OpeningPeriod::opens))
                    .forEach(period -> periods.add(period.opens() + "–" + period.closes()
                            + (period.endsNextDay() ? " (do dia seguinte)" : "")));
        }
        return ToolResult.ok(ToolJson.json(result));
    }

    private static String hhmm(ZonedDateTime time) {
        return time.toLocalTime().withSecond(0).withNano(0).toString();
    }
}
