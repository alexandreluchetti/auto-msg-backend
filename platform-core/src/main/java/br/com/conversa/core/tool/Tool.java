package br.com.conversa.core.tool;

/**
 * Action the conversation engine may run on behalf of the LLM or a flow.
 * Tools are the only source of business facts (price, opening hours, fee). See ADR-002.
 */
public interface Tool {

    /** Name exposed to the LLM, in snake_case, e.g. {@code search_menu}. */
    String name();

    /** Description for the LLM: when to use it and what it returns. */
    String description();

    /** JSON Schema of the input (object with {@code additionalProperties: false}). */
    String inputSchema();

    ToolResult execute(ToolInvocation invocation);
}
