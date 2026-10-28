package br.com.conversa.segment.restaurant.tool;

import br.com.conversa.core.business.BusinessDirectory;
import br.com.conversa.core.business.DeliveryZone;
import br.com.conversa.core.business.OpeningHours;
import br.com.conversa.core.catalog.CatalogItem;
import br.com.conversa.core.catalog.CatalogOption;
import br.com.conversa.core.order.CheckoutRequest;
import br.com.conversa.core.order.OrderDraft;
import br.com.conversa.core.order.OrderLine;
import br.com.conversa.core.tenant.TenantSettings;
import br.com.conversa.core.tool.Tool;
import br.com.conversa.core.tool.ToolInvocation;
import br.com.conversa.core.tool.ToolResult;
import br.com.conversa.segment.restaurant.cart.Cart;
import br.com.conversa.segment.restaurant.cart.CartPricing;
import br.com.conversa.segment.restaurant.cart.CartStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Draft tools of the restaurant (ADR-002): they change only the cart of the conversation, and the
 * checkout tool shows the summary with a confirmation button. No tool confirms an order (D-006);
 * every amount comes from the catalog and the delivery zones (D-007).
 */
public final class CartTools {

    static final int MAX_QUANTITY = 20;
    private static final int MAX_NOTES = 140;
    private static final int MAX_LINES = 30;

    static final Map<String, String> PAYMENT_LABELS = Map.of(
            "cash", "Dinheiro",
            "card_on_delivery", "Cartão (maquininha)",
            "pix_on_delivery", "Pix na entrega/retirada");

    private final BusinessDirectory directory;
    private final TenantSettings settings;
    private final CartStore carts;
    private final Clock clock;

    public CartTools(BusinessDirectory directory, TenantSettings settings, CartStore carts, Clock clock) {
        this.directory = directory;
        this.settings = settings;
        this.carts = carts;
        this.clock = clock;
    }

    public List<Tool> tools() {
        return List.of(new AddToCart(), new UpdateCart(), new ViewCart(), new SetFulfillment(), new SetPayment(),
                new StartCheckout());
    }

    private abstract class CartTool implements Tool {

        @Override
        public ToolResult execute(ToolInvocation invocation) {
            JsonNode input = ToolJson.input(invocation.inputJson());
            return run(invocation, input, carts.load(invocation));
        }

        abstract ToolResult run(ToolInvocation invocation, JsonNode input, Cart cart);

        /** Saves the cart and answers with its current state. */
        ToolResult saved(ToolInvocation invocation, Cart cart) {
            carts.save(invocation, cart);
            return ToolResult.ok(ToolJson.json(view(invocation, cart)));
        }
    }

    private final class AddToCart extends CartTool {

        @Override
        public String name() {
            return "add_to_cart";
        }

        @Override
        public String description() {
            return """
                    Adiciona um item do cardápio ao carrinho do cliente. Use o "code" que veio de \
                    search_menu e as opções escolhidas pelo nome (ex.: "Grande", "Borda Catupiry"). \
                    Devolve o carrinho com os preços calculados pelo sistema.""";
        }

        @Override
        public String inputSchema() {
            return """
                    {"type": "object", "additionalProperties": false, "required": ["code"], "properties": {
                      "code": {"type": "string", "description": "Código do item, vindo de search_menu"},
                      "quantity": {"type": "integer", "minimum": 1, "maximum": 20},
                      "options": {"type": "array", "items": {"type": "string"}, "maxItems": 10},
                      "notes": {"type": "string", "maxLength": 140, "description": "Observação do cliente, ex.: sem cebola"}
                    }}""";
        }

        @Override
        ToolResult run(ToolInvocation invocation, JsonNode input, Cart cart) {
            String code = ToolJson.text(input, "code");
            Optional<CatalogItem> item = directory.catalog(invocation.tenantId()).stream()
                    .filter(candidate -> candidate.code().equals(code)).findFirst();
            if (item.isEmpty()) {
                return ToolResult.error("no item with code " + code + "; look it up with search_menu");
            }
            if (!item.get().available()) {
                return ToolResult.error(item.get().name() + " is out of stock today");
            }
            if (cart.lines().size() >= MAX_LINES) {
                return ToolResult.error("the cart is full");
            }
            List<String> options = new ArrayList<>();
            for (JsonNode wanted : input.path("options")) {
                Optional<CatalogOption> option = CartPricing.optionOf(item.get(), wanted.asString());
                if (option.isEmpty()) {
                    return ToolResult.error("option '" + wanted.asString() + "' not found for " + item.get().name()
                            + "; available: " + item.get().options().stream()
                            .map(candidate -> candidate.group() + ": " + candidate.name()).toList());
                }
                if (options.contains(option.get().name())) {
                    continue;
                }
                options.add(option.get().name());
            }
            int quantity = input.path("quantity").asInt(1);
            String notes = ToolJson.text(input, "notes");
            notes = notes == null ? null : notes.substring(0, Math.min(notes.length(), MAX_NOTES));
            return saved(invocation, cart.withLine(new Cart.Line(item.get().code(), quantity, options, notes)));
        }
    }

    private final class UpdateCart extends CartTool {

        @Override
        public String name() {
            return "update_cart";
        }

        @Override
        public String description() {
            return """
                    Muda a quantidade de uma linha do carrinho (número "line" que aparece em \
                    view_cart). Quantidade 0 remove a linha. Para trocar opções, remova a linha e \
                    adicione o item de novo.""";
        }

        @Override
        public String inputSchema() {
            return """
                    {"type": "object", "additionalProperties": false, "required": ["line", "quantity"], "properties": {
                      "line": {"type": "integer", "minimum": 1},
                      "quantity": {"type": "integer", "minimum": 0, "maximum": 20}
                    }}""";
        }

        @Override
        ToolResult run(ToolInvocation invocation, JsonNode input, Cart cart) {
            int line = input.path("line").asInt();
            if (line < 1 || line > cart.lines().size()) {
                return ToolResult.error("the cart has no line " + line);
            }
            List<Cart.Line> lines = new ArrayList<>(cart.lines());
            int quantity = input.path("quantity").asInt();
            if (quantity == 0) {
                lines.remove(line - 1);
            } else {
                Cart.Line current = lines.get(line - 1);
                lines.set(line - 1, new Cart.Line(current.code(), quantity, current.options(), current.notes()));
            }
            return saved(invocation, cart.withLines(lines));
        }
    }

    private final class ViewCart extends CartTool {

        @Override
        public String name() {
            return "view_cart";
        }

        @Override
        public String description() {
            return """
                    Mostra o carrinho atual: itens com preços do sistema, subtotal, taxa de entrega, \
                    total, forma de entrega, pagamento e o que ainda falta para fechar o pedido \
                    ("missing").""";
        }

        @Override
        public String inputSchema() {
            return """
                    {"type": "object", "additionalProperties": false, "properties": {}}""";
        }

        @Override
        ToolResult run(ToolInvocation invocation, JsonNode input, Cart cart) {
            return ToolResult.ok(ToolJson.json(view(invocation, cart)));
        }
    }

    private final class SetFulfillment extends CartTool {

        @Override
        public String name() {
            return "set_fulfillment";
        }

        @Override
        public String description() {
            return """
                    Define se o pedido é para entrega ("delivery") ou retirada ("pickup"). Para \
                    entrega, informe o endereço (rua, número, complemento e referência) e o bairro, \
                    que precisa ser um dos bairros atendidos (confira com get_delivery_fee).""";
        }

        @Override
        public String inputSchema() {
            return """
                    {"type": "object", "additionalProperties": false, "required": ["type"], "properties": {
                      "type": {"enum": ["delivery", "pickup"]},
                      "address": {"type": "string", "maxLength": 200},
                      "neighborhood": {"type": "string", "maxLength": 80}
                    }}""";
        }

        @Override
        ToolResult run(ToolInvocation invocation, JsonNode input, Cart cart) {
            JsonNode fulfillment = config(invocation).path("fulfillment");
            String type = ToolJson.text(input, "type");
            if ("pickup".equals(type)) {
                if (!fulfillment.path("pickup").asBoolean(false)) {
                    return ToolResult.error("the restaurant does not offer pickup");
                }
                return saved(invocation, cart.withFulfillment("pickup", null, null));
            }
            if (!fulfillment.path("delivery").asBoolean(false)) {
                return ToolResult.error("the restaurant does not deliver");
            }
            String neighborhood = ToolJson.text(input, "neighborhood");
            Optional<DeliveryZone> zone = CartPricing.zoneOf(neighborhood, directory.deliveryZones(invocation.tenantId()));
            if (neighborhood != null && zone.isEmpty()) {
                return ToolResult.error("the restaurant does not deliver to '" + neighborhood
                        + "'; check the name with get_delivery_fee");
            }
            String address = ToolJson.text(input, "address");
            return saved(invocation, cart.withFulfillment("delivery", address,
                    zone.map(DeliveryZone::neighborhood).orElse(null)));
        }
    }

    private final class SetPayment extends CartTool {

        @Override
        public String name() {
            return "set_payment";
        }

        @Override
        public String description() {
            return """
                    Define a forma de pagamento, feita na entrega ou na retirada. Para dinheiro, \
                    pergunte se precisa de troco: informe "changeFor" (valor da nota) ou \
                    "noChangeNeeded": true.""";
        }

        @Override
        public String inputSchema() {
            return """
                    {"type": "object", "additionalProperties": false, "required": ["method"], "properties": {
                      "method": {"enum": ["cash", "card_on_delivery", "pix_on_delivery"]},
                      "changeFor": {"type": "number", "minimum": 0, "maximum": 10000},
                      "noChangeNeeded": {"type": "boolean"}
                    }}""";
        }

        @Override
        ToolResult run(ToolInvocation invocation, JsonNode input, Cart cart) {
            String method = ToolJson.text(input, "method");
            boolean accepted = false;
            for (JsonNode candidate : config(invocation).path("paymentMethods")) {
                accepted |= candidate.asString().equals(method);
            }
            if (!accepted) {
                return ToolResult.error("the restaurant does not accept " + method + "; see get_business_info");
            }
            if (!"cash".equals(method)) {
                return saved(invocation, cart.withPayment(method, null, false));
            }
            BigDecimal changeFor = input.path("changeFor").isNumber() ? input.path("changeFor").decimalValue() : null;
            return saved(invocation, cart.withPayment(method, changeFor, input.path("noChangeNeeded").asBoolean(false)));
        }
    }

    private final class StartCheckout extends CartTool {

        @Override
        public String name() {
            return "start_checkout";
        }

        @Override
        public String description() {
            return """
                    Fecha o carrinho: se estiver tudo certo, o sistema envia ao cliente o resumo do \
                    pedido com o botão "Confirmar pedido" e você não escreve mais nada. Se faltar \
                    algo, devolve o que falta ("missing") para você perguntar ao cliente. Só o \
                    clique do cliente no botão confirma o pedido.""";
        }

        @Override
        public String inputSchema() {
            return """
                    {"type": "object", "additionalProperties": false, "properties": {}}""";
        }

        @Override
        ToolResult run(ToolInvocation invocation, JsonNode input, Cart cart) {
            ObjectNode view = view(invocation, cart);
            if (!view.path("missing").isEmpty() || !view.path("problems").isEmpty()) {
                return new ToolResult(ToolJson.json(view.put("checkoutShown", false)), true);
            }
            OpeningHours hours = directory.openingHours(invocation.tenantId());
            Instant now = clock.instant();
            if (hours.registered() && hours.current(now).isEmpty()) {
                ObjectNode closed = ToolJson.object().put("checkoutShown", false).put("closed", true);
                hours.next(now).ifPresent(next -> closed.put("opensAgain",
                        ToolJson.dayLabel(next.opens().getDayOfWeek()) + " " + next.opens().toLocalTime()));
                return new ToolResult(ToolJson.json(closed), true);
            }
            CartPricing.Priced priced = price(invocation, cart);
            OrderDraft draft = new OrderDraft(cart.fulfillment().toUpperCase(), cart.address(), cart.neighborhood(),
                    priced.deliveryFee(), cart.paymentMethod(), cart.noChangeNeeded() ? null : cart.changeFor(),
                    priced.lines(), priced.subtotal(), priced.total());
            return ToolResult.checkout(ToolJson.json(ToolJson.object().put("checkoutShown", true)),
                    new CheckoutRequest(OrderSummary.text(draft), draft));
        }
    }

    private JsonNode config(ToolInvocation invocation) {
        return ToolJson.input(settings.effectiveConfigJson(invocation.tenantId()));
    }

    private CartPricing.Priced price(ToolInvocation invocation, Cart cart) {
        return CartPricing.price(cart, directory.catalog(invocation.tenantId()),
                directory.deliveryZones(invocation.tenantId()));
    }

    /** The cart as the LLM sees it, with what is still missing to order it. */
    ObjectNode view(ToolInvocation invocation, Cart cart) {
        CartPricing.Priced priced = price(invocation, cart);
        JsonNode config = config(invocation);
        ObjectNode result = ToolJson.object();
        ArrayNode lines = result.putArray("lines");
        for (int index = 0; index < priced.lines().size(); index++) {
            OrderLine line = priced.lines().get(index);
            ObjectNode node = lines.addObject().put("line", index + 1).put("code", line.code())
                    .put("name", line.name()).put("quantity", line.quantity());
            ArrayNode options = node.putArray("options");
            line.options().forEach(options::add);
            if (line.notes() != null) {
                node.put("notes", line.notes());
            }
            ToolJson.putMoney(node, "unitPrice", line.unitPrice());
            ToolJson.putMoney(node, "lineTotal", line.lineTotal());
        }
        ToolJson.putMoney(result, "subtotal", priced.subtotal());
        if ("delivery".equals(cart.fulfillment())) {
            ToolJson.putMoney(result, "deliveryFee", priced.deliveryFee());
        }
        ToolJson.putMoney(result, "total", priced.total());
        if (cart.fulfillment() != null) {
            result.put("fulfillment", cart.fulfillment());
        }
        if (cart.address() != null) {
            result.put("address", cart.address());
        }
        if (cart.neighborhood() != null) {
            result.put("neighborhood", cart.neighborhood());
        }
        if (cart.paymentMethod() != null) {
            result.put("paymentMethod", cart.paymentMethod());
        }
        if (cart.changeFor() != null) {
            ToolJson.putMoney(result, "changeFor", cart.changeFor());
        }

        ArrayNode problems = result.putArray("problems");
        priced.problems().forEach(problems::add);
        ArrayNode missing = result.putArray("missing");
        if (cart.lines().isEmpty()) {
            missing.add("items");
        }
        if (cart.fulfillment() == null) {
            missing.add("fulfillment (delivery or pickup)");
        } else if ("delivery".equals(cart.fulfillment())) {
            if (cart.address() == null) {
                missing.add("delivery address");
            }
            if (priced.zone() == null) {
                missing.add("neighborhood served by the restaurant");
            }
            JsonNode minimum = config.path("fulfillment").path("minimumOrder");
            if (minimum.isNumber() && priced.subtotal().compareTo(minimum.decimalValue()) < 0) {
                missing.add("minimum order for delivery is " + ToolJson.formatMoney(minimum.decimalValue())
                        + " in items");
            }
        }
        if (cart.paymentMethod() == null) {
            missing.add("payment method");
        } else if ("cash".equals(cart.paymentMethod()) && !cart.noChangeNeeded()) {
            if (cart.changeFor() == null) {
                missing.add("change: how much cash the customer will pay with, or that no change is needed");
            } else if (cart.changeFor().compareTo(priced.total()) < 0) {
                missing.add("change: the cash amount is less than the total");
            }
        }
        return result;
    }
}
