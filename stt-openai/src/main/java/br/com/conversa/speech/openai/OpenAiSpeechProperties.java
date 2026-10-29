package br.com.conversa.speech.openai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Configuration of the OpenAI transcription adapter (ADR-012).
 *
 * @param apiKey         API key (environment variable, never versioned); empty turns transcription off
 * @param model          transcription model
 * @param language       ISO-639-1 language of the customers' audio
 * @param baseUrl        API base URL (tests point it at a fake server)
 * @param timeout        timeout of one request
 * @param pricePerMinute USD per minute of audio, to record the cost per tenant
 */
@ConfigurationProperties("conversa.speech.openai")
public record OpenAiSpeechProperties(
        String apiKey,
        @DefaultValue("gpt-4o-mini-transcribe") String model,
        @DefaultValue("pt") String language,
        @DefaultValue("https://api.openai.com") String baseUrl,
        @DefaultValue("60s") Duration timeout,
        @DefaultValue("0.003") BigDecimal pricePerMinute) {
}
