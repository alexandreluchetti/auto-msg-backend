package br.com.conversa.llm.anthropic;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuration of the Claude adapter. The model may be overridden per tenant. See ADR-005.
 *
 * @param apiKey          API key (environment variable, never versioned); empty turns the
 *                        assistant off
 * @param defaultModel    default model when the tenant does not set one
 * @param baseUrl         API base URL; empty uses the SDK default (tests point it at a fake server)
 * @param timeout         timeout of one request, retries included by the SDK
 * @param refusalFallback whether a refused request is retried by the API on a fallback model
 */
@ConfigurationProperties("conversa.llm.anthropic")
public record AnthropicProperties(
        String apiKey,
        @DefaultValue("claude-opus-5-5") String defaultModel,
        String baseUrl,
        @DefaultValue("60s") Duration timeout,
        @DefaultValue("true") boolean refusalFallback) {
}
