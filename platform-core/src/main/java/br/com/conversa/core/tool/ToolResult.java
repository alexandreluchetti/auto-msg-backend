package br.com.conversa.core.tool;

/**
 * Resultado devolvido ao LLM. {@code error = true} informa falha sem interromper o turno.
 */
public record ToolResult(String contentJson, boolean error) {

    public static ToolResult ok(String contentJson) {
        return new ToolResult(contentJson, false);
    }

    public static ToolResult error(String message) {
        return new ToolResult("{\"error\":\"" + message.replace("\"", "'") + "\"}", true);
    }
}
