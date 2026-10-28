package br.com.conversa.app.conversation;

import br.com.conversa.core.tool.Tool;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates what the model passes to a tool against the tool's schema (ADR-002), so a tool only
 * ever runs with input it declared.
 */
@Component
class ToolInputValidator {

    private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private final Map<String, Schema> schemas = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;

    ToolInputValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** The problem with the input, or empty when it is valid. */
    Optional<String> problem(Tool tool, String inputJson) {
        JsonNode input;
        try {
            input = mapper.readTree(inputJson == null || inputJson.isBlank() ? "{}" : inputJson);
        } catch (JacksonException e) {
            return Optional.of("input is not valid JSON");
        }
        Schema schema = schemas.computeIfAbsent(tool.name() + "\n" + tool.inputSchema(),
                key -> registry.getSchema(tool.inputSchema()));
        List<Error> errors = schema.validate(input);
        if (errors.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(String.join("; ", errors.stream()
                .map(error -> error.getInstanceLocation() + " " + error.getMessage())
                .toList()));
    }
}
