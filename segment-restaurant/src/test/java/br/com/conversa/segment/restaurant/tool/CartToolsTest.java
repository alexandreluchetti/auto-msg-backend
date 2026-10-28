package br.com.conversa.segment.restaurant.tool;

import br.com.conversa.core.business.DeliveryZone;
import br.com.conversa.core.business.OpeningHours;
import br.com.conversa.core.business.OpeningPeriod;
import br.com.conversa.core.catalog.CatalogItem;
import br.com.conversa.core.catalog.CatalogOption;
import br.com.conversa.core.conversation.ConversationState;
import br.com.conversa.core.order.OrderDraft;
import br.com.conversa.core.tenant.TenantId;
import br.com.conversa.core.tool.Tool;
import br.com.conversa.core.tool.ToolInvocation;
import br.com.conversa.core.tool.ToolResult;
import br.com.conversa.segment.restaurant.cart.CartStore;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M7: the cart is priced only from the catalog and zones, and the checkout shows a summary only
 * when everything needed is there.
 */
class CartToolsTest {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final TenantId TENANT = TenantId.of("00000000-0000-0000-0000-00000000000a");
    private static final String CONVERSATION = "00000000-0000-0000-0000-0000000000c1";

    private final Map<String, String> states = new HashMap<>();
    private final ConversationState state = new ConversationState() {
        @Override
        public String read(TenantId tenant, String conversationId) {
            return states.getOrDefault(conversationId, "{}");
        }

        @Override
        public void write(TenantId tenant, String conversationId, String stateJson) {
            states.put(conversationId, stateJson);
        }
    };

    private final FakeBusinessDirectory directory = new FakeBusinessDirectory()
            .catalog(TENANT,
                    new CatalogItem(UUID.randomUUID(), "P01", "Pizzas", "Pizza Calabresa", null, new BigDecimal("49.90"), true,
                            List.of(new CatalogOption("Tamanho", "Média (8 fatias)", BigDecimal.ZERO),
                                    new CatalogOption("Tamanho", "Grande (12 fatias)", new BigDecimal("12.00")),
                                    new CatalogOption("Borda", "Catupiry", new BigDecimal("8.00")))),
                    new CatalogItem(UUID.randomUUID(), "P05", "Pizzas", "Pizza Quatro Queijos", null, new BigDecimal("59.00"),
                            false, List.of()),
                    new CatalogItem(UUID.randomUUID(), "B01", "Bebidas", "Refrigerante Lata", null, new BigDecimal("6.50"), true,
                            List.of()))
            .zones(TENANT, new DeliveryZone("Centro", new BigDecimal("5.00")),
                    new DeliveryZone("Jardim Botânico", new BigDecimal("7.00")))
            .hours(TENANT, new OpeningHours(SAO_PAULO, List.of(
                    new OpeningPeriod(DayOfWeek.WEDNESDAY, LocalTime.of(18, 0), LocalTime.of(23, 0)))));

    private String config = """
            {"business": {"name": "Cantina"}, "fulfillment": {"delivery": true, "pickup": true, "minimumOrder": 30},
             "paymentMethods": ["cash", "pix_on_delivery"]}""";

    private Clock clock = at("2026-10-28T20:00"); // Wednesday, open

    private Map<String, Tool> tools() {
        Map<String, Tool> tools = new HashMap<>();
        new CartTools(directory, tenant -> config, new CartStore(state), clock).tools()
                .forEach(tool -> tools.put(tool.name(), tool));
        return tools;
    }

    @Test
    void pricesItemsAndOptionsFromTheCatalog() {
        JsonNode cart = ok("add_to_cart", "{\"code\": \"P01\", \"quantity\": 2, \"options\": [\"grande\", \"borda catupiry\"],"
                + " \"notes\": \"sem cebola\"}");

        JsonNode line = cart.path("lines").path(0);
        assertThat(line.path("options")).extracting(JsonNode::asString)
                .containsExactly("Tamanho: Grande (12 fatias)", "Borda: Catupiry");
        assertThat(line.path("unitPriceFormatted").asString()).isEqualTo("R$ 69,90");
        assertThat(line.path("lineTotalFormatted").asString()).isEqualTo("R$ 139,80");
        assertThat(line.path("notes").asString()).isEqualTo("sem cebola");
        assertThat(cart.path("subtotalFormatted").asString()).isEqualTo("R$ 139,80");
    }

    @Test
    void refusesUnknownUnavailableAndAmbiguousChoices() {
        assertThat(error("add_to_cart", "{\"code\": \"X99\"}")).contains("no item with code X99");
        assertThat(error("add_to_cart", "{\"code\": \"P05\"}")).contains("out of stock");
        assertThat(error("add_to_cart", "{\"code\": \"P01\", \"options\": [\"gigante\"]}"))
                .contains("option 'gigante' not found", "Tamanho: Grande (12 fatias)");
        assertThat(states).isEmpty();
    }

    @Test
    void changesAndRemovesLines() {
        ok("add_to_cart", "{\"code\": \"P01\"}");
        ok("add_to_cart", "{\"code\": \"B01\", \"quantity\": 2}");

        JsonNode cart = ok("update_cart", "{\"line\": 2, \"quantity\": 3}");
        assertThat(cart.path("lines").path(1).path("quantity").asInt()).isEqualTo(3);
        cart = ok("update_cart", "{\"line\": 1, \"quantity\": 0}");
        assertThat(cart.path("lines")).hasSize(1);
        assertThat(cart.path("lines").path(0).path("code").asString()).isEqualTo("B01");
        assertThat(error("update_cart", "{\"line\": 5, \"quantity\": 1}")).contains("no line 5");
    }

    @Test
    void deliveryNeedsAServedNeighborhood() {
        ok("add_to_cart", "{\"code\": \"P01\"}");

        assertThat(error("set_fulfillment", "{\"type\": \"delivery\", \"address\": \"Rua A, 1\", \"neighborhood\": \"Vila X\"}"))
                .contains("does not deliver to 'Vila X'");
        JsonNode cart = ok("set_fulfillment", "{\"type\": \"delivery\", \"address\": \"Rua A, 1\", \"neighborhood\": \"jardim botanico\"}");
        assertThat(cart.path("neighborhood").asString()).isEqualTo("Jardim Botânico");
        assertThat(cart.path("deliveryFeeFormatted").asString()).isEqualTo("R$ 7,00");
        assertThat(cart.path("totalFormatted").asString()).isEqualTo("R$ 56,90");

        config = config.replace("\"pickup\": true", "\"pickup\": false");
        assertThat(error("set_fulfillment", "{\"type\": \"pickup\"}")).contains("does not offer pickup");
    }

    @Test
    void paymentMustBeAccepted() {
        assertThat(error("set_payment", "{\"method\": \"card_on_delivery\"}")).contains("does not accept");
        JsonNode cart = ok("set_payment", "{\"method\": \"cash\", \"changeFor\": 100}");
        assertThat(cart.path("changeForFormatted").asString()).isEqualTo("R$ 100,00");
    }

    @Test
    void checkoutListsWhatIsMissing() {
        ToolResult empty = run("start_checkout", "{}");
        assertThat(empty.error()).isTrue();
        assertThat(empty.checkout()).isNull();
        assertThat(json(empty).path("missing")).extracting(JsonNode::asString)
                .contains("items", "fulfillment (delivery or pickup)", "payment method");

        ok("add_to_cart", "{\"code\": \"B01\"}");
        ok("set_fulfillment", "{\"type\": \"delivery\", \"address\": \"Rua A, 1\", \"neighborhood\": \"Centro\"}");
        ok("set_payment", "{\"method\": \"cash\"}");
        assertThat(json(run("start_checkout", "{}")).path("missing")).extracting(JsonNode::asString).contains(
                "minimum order for delivery is R$ 30,00 in items",
                "change: how much cash the customer will pay with, or that no change is needed");
    }

    @Test
    void checkoutIsRefusedWhileClosed() {
        clock = at("2026-10-29T10:00"); // Thursday morning, closed
        readyToCheckout();

        ToolResult result = run("start_checkout", "{}");

        assertThat(result.checkout()).isNull();
        assertThat(json(result).path("closed").asBoolean()).isTrue();
    }

    @Test
    void aCompleteCartShowsTheSummaryWithDatabaseTotals() {
        readyToCheckout();

        ToolResult result = run("start_checkout", "{}");

        assertThat(result.error()).isFalse();
        OrderDraft draft = result.checkout().draft();
        assertThat(draft.fulfillment()).isEqualTo("DELIVERY");
        assertThat(draft.neighborhood()).isEqualTo("Centro");
        assertThat(draft.subtotal()).isEqualByComparingTo("74.90");
        assertThat(draft.deliveryFee()).isEqualByComparingTo("5.00");
        assertThat(draft.total()).isEqualByComparingTo("79.90");
        assertThat(draft.changeFor()).isEqualByComparingTo("100");
        assertThat(draft.lines()).extracting(line -> line.code()).containsExactly("P01", "B01");
        assertThat(result.checkout().summaryText()).contains(
                "1x Pizza Calabresa (Tamanho: Grande (12 fatias)) — R$ 61,90",
                "2x Refrigerante Lata — R$ 13,00",
                "Taxa de entrega (Centro): R$ 5,00",
                "*Total: R$ 79,90*",
                "Entrega: Rua A, 1 - Centro",
                "Pagamento: Dinheiro (troco para R$ 100,00)",
                "Confirmar pedido");
    }

    private void readyToCheckout() {
        ok("add_to_cart", "{\"code\": \"P01\", \"options\": [\"Grande\"]}");
        ok("add_to_cart", "{\"code\": \"B01\", \"quantity\": 2}");
        ok("set_fulfillment", "{\"type\": \"delivery\", \"address\": \"Rua A, 1\", \"neighborhood\": \"Centro\"}");
        ok("set_payment", "{\"method\": \"cash\", \"changeFor\": 100}");
    }

    private JsonNode ok(String tool, String input) {
        ToolResult result = run(tool, input);
        assertThat(result.error()).as(result.contentJson()).isFalse();
        return json(result);
    }

    private String error(String tool, String input) {
        ToolResult result = run(tool, input);
        assertThat(result.error()).isTrue();
        return result.contentJson();
    }

    private ToolResult run(String tool, String input) {
        return tools().get(tool).execute(new ToolInvocation(TENANT, CONVERSATION, input));
    }

    private static JsonNode json(ToolResult result) {
        return ToolJson.input(result.contentJson());
    }

    private static Clock at(String localDateTime) {
        return Clock.fixed(LocalDateTime.parse(localDateTime).atZone(SAO_PAULO).toInstant(), SAO_PAULO);
    }
}
