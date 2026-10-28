package br.com.conversa.app;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerPropertiesTest {

    private final WorkerProperties properties = new WorkerProperties(true, Duration.ofMillis(500), 20, 5,
            Duration.ofSeconds(5), Duration.ofMinutes(10), Duration.ofMinutes(1));

    @Test
    void retryDelayDoublesPerAttempt() {
        assertThat(properties.retryDelay(1, 0)).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.retryDelay(2, 0)).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.retryDelay(4, 0)).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    void retryDelayIsCappedAndJitterAddsAtMostTwentyPercent() {
        assertThat(properties.retryDelay(50, 0)).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.retryDelay(1, 0.999)).isBetween(Duration.ofSeconds(5), Duration.ofSeconds(6));
    }
}
