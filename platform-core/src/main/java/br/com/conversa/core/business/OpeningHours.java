package br.com.conversa.core.business;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Weekly opening hours of a business in its time zone. No periods means the business did not
 * register its hours (not that it is always closed).
 */
public record OpeningHours(ZoneId zone, List<OpeningPeriod> periods) {

    public OpeningHours {
        periods = List.copyOf(periods);
    }

    /** One concrete opening, e.g. Friday 2026-10-30 18:00 to Saturday 02:00. */
    public record Interval(ZonedDateTime opens, ZonedDateTime closes) {

        boolean contains(Instant instant) {
            return !instant.isBefore(opens.toInstant()) && instant.isBefore(closes.toInstant());
        }
    }

    public boolean registered() {
        return !periods.isEmpty();
    }

    /** The opening that contains {@code now}, if the business is open. */
    public Optional<Interval> current(Instant now) {
        return intervalsAround(now).stream().filter(interval -> interval.contains(now)).findFirst();
    }

    /** The next opening that starts after {@code now}, within a week. */
    public Optional<Interval> next(Instant now) {
        return intervalsAround(now).stream()
                .filter(interval -> interval.opens().toInstant().isAfter(now))
                .findFirst();
    }

    /** Openings from the day before {@code now} to a week after, sorted by start. */
    private List<Interval> intervalsAround(Instant now) {
        LocalDate today = now.atZone(zone).toLocalDate();
        List<Interval> intervals = new ArrayList<>();
        for (int offset = -1; offset <= 7; offset++) {
            LocalDate date = today.plusDays(offset);
            for (OpeningPeriod period : periods) {
                if (period.day() == date.getDayOfWeek()) {
                    LocalDate closingDate = period.endsNextDay() ? date.plusDays(1) : date;
                    intervals.add(new Interval(
                            date.atTime(period.opens()).atZone(zone),
                            closingDate.atTime(period.closes()).atZone(zone)));
                }
            }
        }
        intervals.sort(Comparator.comparing(interval -> interval.opens().toInstant()));
        return intervals;
    }
}
