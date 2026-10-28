package br.com.conversa.core.business;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Objects;

/**
 * The business is open on {@code day} from {@code opens} to {@code closes}, local time. A closing
 * time at or before the opening time ends on the next day (18:00–02:00, or 18:00–00:00).
 */
public record OpeningPeriod(DayOfWeek day, LocalTime opens, LocalTime closes) {

    public OpeningPeriod {
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(opens, "opens");
        Objects.requireNonNull(closes, "closes");
        if (opens.equals(closes)) {
            throw new IllegalArgumentException("Opening and closing times must differ");
        }
    }

    public boolean endsNextDay() {
        return !closes.isAfter(opens);
    }
}
