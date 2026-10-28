package br.com.conversa.core.llm;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The model's answer to one request.
 *
 * @param model           the model that actually answered (a provider fallback may differ)
 * @param providerMessage the provider's form of this answer, replayed by {@link #asAssistantMessage()}
 */
public record LlmResponse(String model, List<LlmContent> content, StopReason stopReason, LlmUsage usage,
                          Object providerMessage) {

    public enum StopReason { END_TURN, TOOL_USE, MAX_TOKENS, REFUSAL, OTHER }

    public LlmResponse {
        content = List.copyOf(content);
    }

    public List<LlmContent.ToolCall> toolCalls() {
        return content.stream()
                .filter(LlmContent.ToolCall.class::isInstance)
                .map(LlmContent.ToolCall.class::cast)
                .toList();
    }

    /** Visible text blocks joined by blank lines. */
    public String text() {
        return content.stream()
                .filter(LlmContent.Text.class::isInstance)
                .map(block -> ((LlmContent.Text) block).text().strip())
                .filter(text -> !text.isEmpty())
                .collect(Collectors.joining("\n\n"));
    }

    /** This answer as the assistant turn of the next request of the same turn, unchanged. */
    public LlmMessage asAssistantMessage() {
        return new LlmMessage(LlmMessage.Role.ASSISTANT, content, providerMessage);
    }
}
