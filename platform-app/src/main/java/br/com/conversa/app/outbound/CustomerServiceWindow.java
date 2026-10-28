package br.com.conversa.app.outbound;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The 24h customer service window: free text may be sent only within 24h of the customer's last
 * message. It counts from when the customer sent it (Meta's {@code timestamp}), not from when we
 * received it, and staff echoes from the app do not count (ADR-001).
 */
@Component
public class CustomerServiceWindow {

    static final Duration LENGTH = Duration.ofHours(24);

    private final JdbcClient jdbc;
    private final Clock clock;

    public CustomerServiceWindow(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public boolean isOpen(String phoneNumberId, String waId) {
        Long lastMessageEpochSecond = jdbc.sql("""
                        SELECT max((payload #>> '{item,timestamp}')::bigint)
                        FROM inbound_event
                        WHERE event_type = 'MESSAGE' AND phone_number_id = :phoneNumberId AND wa_id = :waId
                        """)
                .param("phoneNumberId", phoneNumberId)
                .param("waId", waId)
                .query(Long.class).optional().orElse(null);
        return lastMessageEpochSecond != null
                && Instant.ofEpochSecond(lastMessageEpochSecond).plus(LENGTH).isAfter(clock.instant());
    }
}
