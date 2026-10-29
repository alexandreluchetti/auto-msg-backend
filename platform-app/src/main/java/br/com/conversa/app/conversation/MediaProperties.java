package br.com.conversa.app.conversation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Limits of the media the assistant reads (ADR-011 step H6, ADR-012).
 *
 * @param maxAudio      longest voice note transcribed; longer ones are asked to be written
 * @param maxAudioBytes largest audio file downloaded
 * @param maxImageBytes largest image downloaded (the model accepts up to 5 MB)
 */
@ConfigurationProperties("conversa.media")
public record MediaProperties(
        @DefaultValue("2m") Duration maxAudio,
        @DefaultValue("5242880") long maxAudioBytes,
        @DefaultValue("5242880") long maxImageBytes) {
}
