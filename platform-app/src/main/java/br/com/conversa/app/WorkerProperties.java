package br.com.conversa.app;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Background workers of the inbound and outbound queues (ADR-004).
 *
 * @param enabled        runs the workers on a schedule; tests turn it off and drive them by hand
 * @param pollInterval   pause between polls
 * @param batchSize      rows handled per poll
 * @param maxAttempts    attempts before a row is marked {@code FAILED}
 * @param retryBaseDelay first retry delay, doubled on each attempt
 * @param retryMaxDelay  upper bound of the retry delay
 * @param sendLease      how long a claimed outbound row stays reserved for the sender
 */
@ConfigurationProperties("conversa.workers")
public record WorkerProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("500ms") Duration pollInterval,
        @DefaultValue("20") int batchSize,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("5s") Duration retryBaseDelay,
        @DefaultValue("10m") Duration retryMaxDelay,
        @DefaultValue("1m") Duration sendLease) {

    /**
     * Exponential backoff with up to 20% jitter, so retries of a burst do not line up.
     *
     * @param attempt attempts made so far (1 after the first failure)
     * @param jitter  random value in [0, 1)
     */
    public Duration retryDelay(int attempt, double jitter) {
        long max = retryMaxDelay.toMillis();
        long delay = retryBaseDelay.toMillis();
        for (int i = 1; i < attempt && delay < max; i++) {
            delay *= 2;
        }
        delay = Math.min(delay, max);
        return Duration.ofMillis(delay + Math.round(delay * 0.2 * jitter));
    }
}
