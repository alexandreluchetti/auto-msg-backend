package br.com.conversa.app.tenant;

import br.com.conversa.core.segment.SegmentPack;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Effective configuration of a tenant: the pack's defaults with the tenant's own settings merged
 * over them (precedence pack &lt; tenant, ADR-003), validated against the pack's JSON Schema.
 * Tenants store only their own settings, so a new pack default reaches every tenant that did not
 * override it.
 */
@Component
public class TenantConfigResolver {

    private final ObjectMapper mapper;
    private final SchemaRegistry schemas = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private final Map<String, Schema> compiled = new ConcurrentHashMap<>();

    public TenantConfigResolver(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param tenantConfigJson the tenant's own settings, a JSON object
     * @throws InvalidTenantConfigException if the result does not satisfy the schema
     */
    public JsonNode effectiveConfig(SegmentPack pack, String tenantConfigJson) {
        JsonNode overrides = parseObject(tenantConfigJson);
        JsonNode merged = merge(mapper.readTree(pack.defaultConfigJson()), overrides);
        List<Error> errors = schemaOf(pack).validate(merged);
        if (!errors.isEmpty()) {
            throw new InvalidTenantConfigException(errors.stream()
                    .map(error -> error.getInstanceLocation() + ": " + error.getMessage())
                    .toList());
        }
        return merged;
    }

    /** Objects merge field by field; arrays and values of the tenant replace the default. */
    static JsonNode merge(JsonNode defaults, JsonNode overrides) {
        if (!(defaults instanceof ObjectNode base) || !overrides.isObject()) {
            return overrides.deepCopy();
        }
        ObjectNode result = base.deepCopy();
        overrides.properties().forEach(entry -> {
            JsonNode current = result.get(entry.getKey());
            result.set(entry.getKey(), current == null ? entry.getValue().deepCopy() : merge(current, entry.getValue()));
        });
        return result;
    }

    private JsonNode parseObject(String json) {
        JsonNode node;
        try {
            node = mapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (JacksonException e) {
            throw new InvalidTenantConfigException(List.of("$: not valid JSON"));
        }
        if (!node.isObject()) {
            throw new InvalidTenantConfigException(List.of("$: must be a JSON object"));
        }
        return node;
    }

    private Schema schemaOf(SegmentPack pack) {
        return compiled.computeIfAbsent(pack.id() + "@" + pack.version(),
                key -> schemas.getSchema(pack.configSchemaJson()));
    }
}
