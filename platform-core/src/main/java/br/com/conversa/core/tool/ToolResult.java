package br.com.conversa.core.tool;

/**
 * Result returned to the LLM. {@code error = true} reports a failure without aborting the turn.
 */
public record ToolResult(String contentJson, boolean error) {

    public static ToolResult ok(String contentJson) {
        return new ToolResult(contentJson, false);
    }

    public static ToolResult error(String message) {
        return new ToolResult("{\"error\":\"" + message.replace("\"", "'") + "\"}", true);
    }
}
