package br.com.conversa.core.llm;

/** Tokens billed for one request. */
public record LlmUsage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {
}
