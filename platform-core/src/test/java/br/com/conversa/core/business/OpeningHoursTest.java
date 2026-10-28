package br.com.conversa.core.business;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpeningHoursTest {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    // Tuesday to Sunday 18:00–23:00; Friday and Saturday until 02:00; closed on Mondays.
    private final OpeningHours hours = new OpeningHours(SAO_PAULO, List.of(
            period(DayOfWeek.TUESDAY, "18:00", "23:00"),
            period(DayOfWeek.WEDNESDAY, "18:00", "23:00"),
            period(DayOfWeek.THURSDAY, "18:00", "23:00"),
            period(DayOfWeek.FRIDAY, "18:00", "02:00"),
            period(DayOfWeek.SATURDAY, "18:00", "02:00"),
            period(DayOfWeek.SUNDAY, "11:00", "15:00"),
            period(DayOfWeek.SUNDAY, "18:00", "23:00")));

    @Test
    void isOpenInsideAPeriod() {
        var current = hours.current(at("2026-10-28T20:00")); // Wednesday

        assertThat(current).isPresent();
        assertThat(current.get().closes().toLocalDateTime()).isEqualTo(LocalDateTime.parse("2026-10-28T23:00"));
    }

    @Test
    void closingTimeIsExclusive() {
        assertThat(hours.current(at("2026-10-28T23:00"))).isEmpty();
        assertThat(hours.current(at("2026-10-28T18:00"))).isPresent();
    }

    @Test
    void aPeriodPastMidnightStillCountsOnTheNextDay() {
        var current = hours.current(at("2026-10-31T01:30")); // Saturday, from Friday's opening

        assertThat(current).isPresent();
        assertThat(current.get().opens().toLocalDateTime()).isEqualTo(LocalDateTime.parse("2026-10-30T18:00"));
    }

    @Test
    void nextOpeningSkipsClosedDays() {
        var next = hours.next(at("2026-11-01T23:30")); // Sunday night; Monday is closed

        assertThat(next).isPresent();
        assertThat(next.get().opens().toLocalDateTime()).isEqualTo(LocalDateTime.parse("2026-11-03T18:00"));
    }

    @Test
    void nextOpeningOnTheSameDayBetweenTwoPeriods() {
        var next = hours.next(at("2026-11-01T16:00")); // Sunday afternoon

        assertThat(hours.current(at("2026-11-01T16:00"))).isEmpty();
        assertThat(next.orElseThrow().opens().toLocalDateTime()).isEqualTo(LocalDateTime.parse("2026-11-01T18:00"));
    }

    @Test
    void midnightClosingEndsOnTheNextDay() {
        var lateNight = new OpeningHours(SAO_PAULO, List.of(period(DayOfWeek.MONDAY, "19:00", "00:00")));

        assertThat(lateNight.current(at("2026-11-02T23:59"))).isPresent();
        assertThat(lateNight.current(at("2026-11-03T00:00"))).isEmpty();
    }

    @Test
    void withoutPeriodsTheHoursAreUnknown() {
        var unknown = new OpeningHours(SAO_PAULO, List.of());

        assertThat(unknown.registered()).isFalse();
        assertThat(unknown.current(at("2026-10-28T20:00"))).isEmpty();
        assertThat(unknown.next(at("2026-10-28T20:00"))).isEmpty();
    }

    @Test
    void rejectsAnEmptyPeriod() {
        assertThatThrownBy(() -> period(DayOfWeek.MONDAY, "18:00", "18:00"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static OpeningPeriod period(DayOfWeek day, String opens, String closes) {
        return new OpeningPeriod(day, LocalTime.parse(opens), LocalTime.parse(closes));
    }

    private static Instant at(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(SAO_PAULO).toInstant();
    }
}
