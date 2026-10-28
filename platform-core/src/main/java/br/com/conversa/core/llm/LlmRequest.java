package br.com.conversa.core.llm;

import java.util.List;

/**
 * One model request. {@code system} and {@code tools} must stay identical across the requests of a
 * conversation so the provider can cache that prefix; volatile context goes in the last message.
 *
 * @param effort provider-neutral reasoning effort: {@code low}, {@code medium} or {@code high}
 */
public record LlmRequest(
        String model,
        String effort,
        int maxTokens,
        String system,
        List<ToolSpec> tools,
        List<LlmMessage> messages) {

    public LlmRequest {
        tools = List.copyOf(tools);
        messages = List.copyOf(messages);
    }
}
