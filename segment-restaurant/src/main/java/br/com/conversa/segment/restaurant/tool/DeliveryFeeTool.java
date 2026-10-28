package br.com.conversa.segment.restaurant.tool;

import br.com.conversa.core.business.BusinessDirectory;
import br.com.conversa.core.business.DeliveryZone;
import br.com.conversa.core.tenant.TenantSettings;
import br.com.conversa.core.text.SearchText;
import br.com.conversa.core.tool.Tool;
import br.com.conversa.core.tool.ToolInvocation;
import br.com.conversa.core.tool.ToolResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.List;

/**
 * Whether the restaurant delivers to a neighborhood, and the fee. Names match ignoring case and
 * accents; near misses come back as suggestions for the LLM to confirm with the customer.
 */
public final class DeliveryFeeTool implements Tool {

    private static final int MAX_SUGGESTIONS = 3;

    private final BusinessDirectory directory;
    private final TenantSettings settings;

    public DeliveryFeeTool(BusinessDirectory directory, TenantSettings settings) {
        this.directory = directory;
        this.settings = settings;
    }

    @Override
    public String name() {
        return "get_delivery_fee";
    }

    @Override
    public String description() {
        return """
                Diz se o restaurante entrega em um bairro e qual a taxa de entrega. Use quando o \
                cliente perguntar se entregam no bairro dele ou quanto é a entrega; nunca informe \
                taxa que não veio desta ferramenta. Se "served" for false e houver "suggestions", \
                pergunte ao cliente se ele quis dizer um desses bairros. Sem "neighborhood", lista \
                todos os bairros atendidos.""";
    }

    @Override
    public String inputSchema() {
        return """
                {"type": "object", "additionalProperties": false, "properties": {
                  "neighborhood": {"type": "string", "description": "Bairro informado pelo cliente"}
                }}""";
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        String neighborhood = ToolJson.text(ToolJson.input(invocation.inputJson()), "neighborhood");
        JsonNode config = ToolJson.input(settings.effectiveConfigJson(invocation.tenantId()));
        ObjectNode result = ToolJson.object();
        if (!config.path("fulfillment").path("delivery").asBoolean(false)) {
            result.put("deliveryAvailable", false);
            return ToolResult.ok(ToolJson.json(result));
        }
        result.put("deliveryAvailable", true);
        JsonNode minimumOrder = config.path("fulfillment").path("minimumOrder");
        if (minimumOrder.isNumber()) {
            ToolJson.putMoney(result, "minimumOrder", minimumOrder.decimalValue());
        }
        List<DeliveryZone> zones = directory.deliveryZones(invocation.tenantId());
        if (zones.isEmpty()) {
            result.put("zonesRegistered", false);
            return ToolResult.ok(ToolJson.json(result));
        }
        if (neighborhood == null) {
            ArrayNode all = result.putArray("zones");
            zones.forEach(zone -> all.add(zone(zone)));
            return ToolResult.ok(ToolJson.json(result));
        }

        String wanted = SearchText.normalize(neighborhood);
        var match = zones.stream().filter(zone -> SearchText.normalize(zone.neighborhood()).equals(wanted)).findFirst();
        result.put("served", match.isPresent());
        if (match.isPresent()) {
            result.setAll(zone(match.get()));
        } else {
            ArrayNode suggestions = result.putArray("suggestions");
            zones.stream()
                    .filter(zone -> isNear(SearchText.normalize(zone.neighborhood()), wanted))
                    .limit(MAX_SUGGESTIONS)
                    .forEach(zone -> suggestions.add(zone.neighborhood()));
        }
        return ToolResult.ok(ToolJson.json(result));
    }

    private static ObjectNode zone(DeliveryZone zone) {
        ObjectNode node = ToolJson.object().put("neighborhood", zone.neighborhood());
        ToolJson.putMoney(node, "fee", zone.fee() == null ? BigDecimal.ZERO : zone.fee());
        return node;
    }

    /** "jd botanico" vs "jardim botanico", "botanico" vs "jardim botanico", one typo per 5 letters. */
    private static boolean isNear(String zone, String wanted) {
        if (wanted.length() < 3) {
            return false;
        }
        if (zone.contains(wanted) || wanted.contains(zone)) {
            return true;
        }
        int tolerance = Math.max(1, Math.min(zone.length(), wanted.length()) / 5);
        return SearchText.distance(zone, wanted) <= tolerance;
    }
}
