package br.com.conversa.core.tool;

import br.com.conversa.core.order.CheckoutRequest;

/**
 * Result returned to the LLM. {@code error = true} reports a failure without aborting the turn. A
 * {@code checkout} ends the turn: the platform shows that order summary for confirmation.
 */
public record ToolResult(String contentJson, boolean error, CheckoutRequest checkout) {

    public ToolResult(String contentJson, boolean error) {
        this(contentJson, error, null);
    }

    public static ToolResult ok(String contentJson) {
        return new ToolResult(contentJson, false);
    }

    public static ToolResult error(String message) {
        return new ToolResult("{\"error\":\"" + message.replace("\"", "'") + "\"}", true);
    }

    public static ToolResult checkout(String contentJson, CheckoutRequest checkout) {
        return new ToolResult(contentJson, false, checkout);
    }
}
