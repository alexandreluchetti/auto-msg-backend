package br.com.conversa.segment.restaurant.tool;

import br.com.conversa.core.business.DeliveryZone;
import br.com.conversa.core.business.OpeningHours;
import br.com.conversa.core.business.OpeningPeriod;
import br.com.conversa.core.catalog.CatalogItem;
import br.com.conversa.core.catalog.CatalogOption;
import br.com.conversa.core.tenant.TenantId;
import br.com.conversa.core.tenant.TenantSettings;
import br.com.conversa.core.tool.Tool;
import br.com.conversa.core.tool.ToolInvocation;
import br.com.conversa.core.tool.ToolResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4 acceptance: the tools return the asking tenant's own data, and say so when it is missing
 * instead of making it up.
 */
class RestaurantToolsTest {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final TenantId CANTINA = TenantId.of("00000000-0000-0000-0000-00000000000a");
    private static final TenantId BURGER = TenantId.of("00000000-0000-0000-0000-00000000000b");

    // Wednesday 2026-10-28, 20:00 in São Paulo.
    private final Clock clock = Clock.fixed(LocalDateTime.parse("2026-10-28T20:00").atZone(SAO_PAULO).toInstant(), SAO_PAULO);

    private final FakeBusinessDirectory directory = new FakeBusinessDirectory()
            .catalog(CANTINA,
                    item("P01", "Pizzas", "Pizza Calabresa", "39.90", true,
                            new CatalogOption("Tamanho", "Grande", new BigDecimal("10"))),
                    item("P02", "Pizzas", "Pizza Margherita", "42.00", false),
                    item("B01", "Bebidas", "Refrigerante Lata", "6.50", true))
            .catalog(BURGER, item("H01", "Lanches", "X-Calabresa", "28.00", true))
            .hours(CANTINA, new OpeningHours(SAO_PAULO, List.of(
                    new OpeningPeriod(DayOfWeek.WEDNESDAY, LocalTime.of(18, 0), LocalTime.of(23, 0)),
                    new OpeningPeriod(DayOfWeek.FRIDAY, LocalTime.of(18, 0), LocalTime.of(2, 0)))))
            .zones(CANTINA,
                    new DeliveryZone("Centro", new BigDecimal("5")),
                    new DeliveryZone("Jardim Botânico", new BigDecimal("8.5")))
            .zones(BURGER, new DeliveryZone("Vila Nova", new BigDecimal("3")));

    private final TenantSettings settings = tenant -> Map.of(
            CANTINA, """
                    {"business": {"name": "Cantina da Praça", "address": "Rua Sete, 100", "city": "Bonfim Paulista"},
                     "fulfillment": {"delivery": true, "pickup": true, "minimumOrder": 30},
                     "paymentMethods": ["cash", "pix_on_delivery"]}""",
            BURGER, """
                    {"business": {"name": "Burger"}, "fulfillment": {"delivery": false, "pickup": true},
                     "paymentMethods": ["card_on_delivery"]}""").get(tenant);

    private final Tool searchMenu = new SearchMenuTool(directory);
    private final Tool businessHours = new BusinessHoursTool(directory, clock);
    private final Tool deliveryFee = new DeliveryFeeTool(directory, settings);
    private final Tool businessInfo = new BusinessInfoTool(settings);

    @Test
    void searchMenuReturnsTheTenantsItemsWithFormattedPrices() {
        JsonNode result = run(searchMenu, CANTINA, "{\"query\": \"calabresa\"}");

        assertThat(result.path("items")).hasSize(1);
        JsonNode pizza = result.path("items").path(0);
        assertThat(pizza.path("code").asString()).isEqualTo("P01");
        assertThat(pizza.path("price").decimalValue()).isEqualByComparingTo("39.90");
        assertThat(pizza.path("priceFormatted").asString()).isEqualTo("R$ 39,90");
        assertThat(pizza.path("options").path(0).path("priceDeltaFormatted").asString()).isEqualTo("R$ 10,00");

        JsonNode burger = run(searchMenu, BURGER, "{\"query\": \"calabresa\"}");
        assertThat(burger.path("items").path(0).path("code").asString()).isEqualTo("H01");
    }

    @Test
    void searchMenuFlagsUnavailableItems() {
        JsonNode result = run(searchMenu, CANTINA, "{\"query\": \"margherita\"}");

        assertThat(result.path("items").path(0).path("available").asBoolean()).isFalse();
    }

    @Test
    void searchMenuWithoutQueryListsCategories() {
        JsonNode result = run(searchMenu, CANTINA, "{}");

        assertThat(result.has("items")).isFalse();
        assertThat(result.path("categories")).hasSize(2);
        assertThat(result.path("categories").path(0).path("name").asString()).isEqualTo("Pizzas");
        assertThat(result.path("categories").path(0).path("items").asInt()).isEqualTo(2);
    }

    @Test
    void searchMenuWithNoMatchReturnsNoItemsAndTheCategories() {
        JsonNode result = run(searchMenu, CANTINA, "{\"query\": \"sushi\"}");

        assertThat(result.path("items")).isEmpty();
        assertThat(result.path("categories")).hasSize(2);
    }

    @Test
    void searchMenuSaysWhenThereIsNoMenu() {
        JsonNode result = run(searchMenu, TenantId.of("00000000-0000-0000-0000-00000000000c"), "{\"query\": \"pizza\"}");

        assertThat(result.path("menuRegistered").asBoolean(true)).isFalse();
    }

    @Test
    void businessHoursTellsWhetherItIsOpenNow() {
        JsonNode result = run(businessHours, CANTINA, "{}");

        assertThat(result.path("openNow").asBoolean()).isTrue();
        assertThat(result.path("closesAt").asString()).isEqualTo("23:00");
        assertThat(result.path("now").path("day").asString()).isEqualTo("quarta-feira");
        assertThat(result.path("nextOpening").path("day").asString()).isEqualTo("sexta-feira");
        assertThat(result.path("nextOpening").path("time").asString()).isEqualTo("18:00");
        assertThat(result.path("week")).hasSize(7);
        assertThat(result.path("week").path(0).path("periods")).isEmpty(); // Monday
        assertThat(result.path("week").path(4).path("periods").path(0).asString())
                .isEqualTo("18:00–02:00 (do dia seguinte)");
    }

    @Test
    void businessHoursSaysWhenTheyAreNotRegistered() {
        JsonNode result = run(businessHours, BURGER, "{}");

        assertThat(result.path("hoursRegistered").asBoolean(true)).isFalse();
        assertThat(result.has("openNow")).isFalse();
    }

    @Test
    void deliveryFeeMatchesIgnoringCaseAndAccents() {
        JsonNode result = run(deliveryFee, CANTINA, "{\"neighborhood\": \"jardim botanico\"}");

        assertThat(result.path("served").asBoolean()).isTrue();
        assertThat(result.path("neighborhood").asString()).isEqualTo("Jardim Botânico");
        assertThat(result.path("feeFormatted").asString()).isEqualTo("R$ 8,50");
        assertThat(result.path("minimumOrderFormatted").asString()).isEqualTo("R$ 30,00");
    }

    @Test
    void deliveryFeeSuggestsNearNamesWithoutServingThem() {
        JsonNode typo = run(deliveryFee, CANTINA, "{\"neighborhood\": \"Jardim Botanica\"}");
        JsonNode partial = run(deliveryFee, CANTINA, "{\"neighborhood\": \"botânico\"}");
        JsonNode unknown = run(deliveryFee, CANTINA, "{\"neighborhood\": \"Vila Nova\"}");

        assertThat(typo.path("served").asBoolean()).isFalse();
        assertThat(typo.path("suggestions").path(0).asString()).isEqualTo("Jardim Botânico");
        assertThat(partial.path("suggestions").path(0).asString()).isEqualTo("Jardim Botânico");
        assertThat(unknown.path("served").asBoolean()).isFalse();
        assertThat(unknown.path("suggestions")).isEmpty();
        assertThat(unknown.has("fee")).isFalse();
    }

    @Test
    void deliveryFeeListsZonesAndHonorsDisabledDelivery() {
        JsonNode all = run(deliveryFee, CANTINA, "{}");
        JsonNode burger = run(deliveryFee, BURGER, "{\"neighborhood\": \"Vila Nova\"}");

        assertThat(all.path("zones")).hasSize(2);
        assertThat(burger.path("deliveryAvailable").asBoolean(true)).isFalse();
        assertThat(burger.has("fee")).isFalse();
    }

    @Test
    void businessInfoComesFromTheTenantConfiguration() {
        JsonNode cantina = run(businessInfo, CANTINA, "{}");
        JsonNode burger = run(businessInfo, BURGER, "{}");

        assertThat(cantina.path("name").asString()).isEqualTo("Cantina da Praça");
        assertThat(cantina.path("address").asString()).isEqualTo("Rua Sete, 100");
        assertThat(cantina.path("paymentMethods")).extracting(node -> node.path("id").asString())
                .containsExactly("cash", "pix_on_delivery");
        assertThat(cantina.path("paymentMethods").path(1).path("label").asString()).isEqualTo("Pix na entrega ou retirada");
        assertThat(cantina.path("onlinePayment").asBoolean(true)).isFalse();
        assertThat(burger.path("delivery").asBoolean(true)).isFalse();
        assertThat(burger.has("address")).isFalse();
    }

    private static JsonNode run(Tool tool, TenantId tenant, String input) {
        ToolResult result = tool.execute(new ToolInvocation(tenant, "conversation-1", input));
        assertThat(result.error()).isFalse();
        return ToolJson.input(result.contentJson());
    }

    private static CatalogItem item(String code, String category, String name, String price, boolean available,
                                    CatalogOption... options) {
        return new CatalogItem(UUID.randomUUID(), code, category, name, null, new BigDecimal(price), available,
                List.of(options));
    }
}
