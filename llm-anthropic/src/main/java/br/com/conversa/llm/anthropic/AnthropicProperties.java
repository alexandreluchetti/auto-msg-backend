package br.com.conversa.llm.anthropic;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration of the Claude adapter. The model may be overridden per tenant. See ADR-005.
 *
 * @param apiKey       API key (environment variable, never versioned)
 * @param defaultModel default model when the tenant does not set one
 */
@ConfigurationProperties("conversa.llm.anthropic")
public record AnthropicProperties(String apiKey, String defaultModel) {
}
