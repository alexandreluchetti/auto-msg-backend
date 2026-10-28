package br.com.conversa.core.llm;

/** A tool as the model sees it: name, description and JSON Schema of its input. */
public record ToolSpec(String name, String description, String inputSchemaJson) {
}
