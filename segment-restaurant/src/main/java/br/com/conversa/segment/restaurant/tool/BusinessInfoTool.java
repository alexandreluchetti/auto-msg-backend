package br.com.conversa.segment.restaurant.tool;

import br.com.conversa.core.tenant.TenantSettings;
import br.com.conversa.core.tool.Tool;
import br.com.conversa.core.tool.ToolInvocation;
import br.com.conversa.core.tool.ToolResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Name, address, delivery/pickup, minimum order and payment methods, from the tenant's
 * configuration.
 */
public final class BusinessInfoTool implements Tool {

    private static final Map<String, String> PAYMENT_LABELS = Map.of(
            "cash", "Dinheiro na entrega ou retirada",
            "card_on_delivery", "Cartão na entrega ou retirada (maquininha)",
            "pix_on_delivery", "Pix na entrega ou retirada");

    private final TenantSettings settings;

    public BusinessInfoTool(TenantSettings settings) {
        this.settings = settings;
    }

    @Override
    public String name() {
        return "get_business_info";
    }

    @Override
    public String description() {
        return """
                Dados do restaurante: nome, endereço, cidade, se faz entrega e/ou retirada, pedido \
                mínimo e formas de pagamento aceitas. O pagamento é sempre feito na entrega ou na \
                retirada ("onlinePayment": false); não ofereça outras formas. Use para perguntas \
                sobre endereço, formas de pagamento ou se fazem entrega.""";
    }

    @Override
    public String inputSchema() {
        return """
                {"type": "object", "additionalProperties": false, "properties": {}}""";
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        JsonNode config = ToolJson.input(settings.effectiveConfigJson(invocation.tenantId()));
        JsonNode business = config.path("business");
        JsonNode fulfillment = config.path("fulfillment");

        ObjectNode result = ToolJson.object().put("name", business.path("name").asString());
        copyText(business, "address", result);
        copyText(business, "city", result);
        result.put("delivery", fulfillment.path("delivery").asBoolean(false));
        result.put("pickup", fulfillment.path("pickup").asBoolean(false));
        if (fulfillment.path("minimumOrder").isNumber()) {
            ToolJson.putMoney(result, "minimumOrder", fulfillment.path("minimumOrder").decimalValue());
        }
        ArrayNode payments = result.putArray("paymentMethods");
        config.path("paymentMethods").forEach(method -> payments.addObject()
                .put("id", method.asString())
                .put("label", PAYMENT_LABELS.getOrDefault(method.asString(), method.asString())));
        result.put("onlinePayment", false);
        return ToolResult.ok(ToolJson.json(result));
    }

    private static void copyText(JsonNode from, String field, ObjectNode to) {
        if (from.path(field).isString() && !from.path(field).asString().isBlank()) {
            to.put(field, from.path(field).asString());
        }
    }
}
