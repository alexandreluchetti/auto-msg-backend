package br.com.conversa.core.llm;

/**
 * A block of a message, in neutral form.
 */
public sealed interface LlmContent {

    record Text(String text) implements LlmContent {
    }

    /** The model asks to run a tool; {@code inputJson} is the raw JSON object it produced. */
    record ToolCall(String id, String name, String inputJson) implements LlmContent {
    }

    /** Result of a {@link ToolCall}, sent back in the next user message. */
    record ToolOutcome(String toolCallId, String contentJson, boolean error) implements LlmContent {
    }
}
