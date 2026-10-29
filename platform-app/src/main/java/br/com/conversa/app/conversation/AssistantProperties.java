package br.com.conversa.app.conversation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * The bot's conversation engine (ADR-002).
 *
 * @param enabled         answers customers when an LLM is configured; off only records messages
 * @param debounce        silence to wait after a customer message before answering the burst
 * @param defaultEffort   reasoning effort when the tenant does not set one
 * @param maxTokens       output limit of one model request
 * @param maxToolRounds   tool round trips per answer before giving up with a fallback message
 * @param historyMessages latest messages of the conversation sent to the model
 * @param replyLease      how long a claimed conversation stays reserved for the reply worker
 * @param maxAttempts     attempts on temporary LLM failures before the fallback message is sent
 * @param retryDelay      wait before retrying a temporary LLM failure
 * @param paceScale       multiplies the human-pace delays (1; tests use 0 to send at once)
 */
@ConfigurationProperties("conversa.assistant")
public record AssistantProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("2s") Duration debounce,
        @DefaultValue("low") String defaultEffort,
        @DefaultValue("4096") int maxTokens,
        @DefaultValue("4") int maxToolRounds,
        @DefaultValue("30") int historyMessages,
        @DefaultValue("2m") Duration replyLease,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("5s") Duration retryDelay,
        @DefaultValue("1") double paceScale) {
}
