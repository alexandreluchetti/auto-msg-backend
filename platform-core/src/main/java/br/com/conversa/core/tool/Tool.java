package br.com.conversa.core.tool;

/**
 * Ação que o motor de conversa pode executar a pedido do LLM ou de um fluxo.
 * Ferramentas são a única fonte de fatos de negócio (preço, horário, taxa). Ver ADR-002.
 */
public interface Tool {

    /** Nome exposto ao LLM, em snake_case, ex.: {@code search_menu}. */
    String name();

    /** Descrição para o LLM: quando usar e o que retorna. */
    String description();

    /** JSON Schema do input (objeto com {@code additionalProperties: false}). */
    String inputSchema();

    ToolResult execute(ToolInvocation invocation);
}
