package br.com.conversa.core.llm;

import java.util.List;

/**
 * A turn of the conversation sent to the model.
 *
 * @param providerMessage the provider's own form of an assistant turn the model produced, sent back
 *                        unchanged instead of {@code content} (it may hold reasoning blocks the
 *                        provider requires verbatim); {@code null} for turns the engine builds
 */
public record LlmMessage(Role role, List<LlmContent> content, Object providerMessage) {

    public enum Role { USER, ASSISTANT }

    public LlmMessage {
        content = List.copyOf(content);
    }

    public LlmMessage(Role role, List<LlmContent> content) {
        this(role, content, null);
    }

    public static LlmMessage user(String text) {
        return new LlmMessage(Role.USER, List.of(new LlmContent.Text(text)));
    }

    public static LlmMessage assistant(String text) {
        return new LlmMessage(Role.ASSISTANT, List.of(new LlmContent.Text(text)));
    }
}
