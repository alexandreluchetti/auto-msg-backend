package br.com.conversa.app.business;

import br.com.conversa.app.TestcontainersConfiguration;
import br.com.conversa.app.tenant.TenantService;
import br.com.conversa.app.tenant.TenantTransactions;
import br.com.conversa.core.business.BusinessDirectory;
import br.com.conversa.core.catalog.CatalogItem;
import br.com.conversa.core.segment.SegmentPack;
import br.com.conversa.core.tenant.TenantId;
import br.com.conversa.core.tool.Tool;
import br.com.conversa.core.tool.ToolInvocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M4: spreadsheets load a tenant's catalog, hours and zones; the tools read them back for that
 * tenant only.
 */
@SpringBootTest(properties = "conversa.workers.enabled=false")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class BusinessDataImportIT {

    private static final String MENU = """
            code;category;name;description;price;available
            P01;Pizzas;Pizza Calabresa;Calabresa e cebola;39,90;sim
            P02;Pizzas;Pizza Margherita;Mussarela e manjericão;42,00;não
            B01;Bebidas;Refrigerante Lata;350 ml;6,50;
            """;
    private static final String OPTIONS = """
            item_code,group,name,price_delta
            P01,Tamanho,Grande,10
            """;

    @Autowired
    BusinessDataImporter importer;

    @Autowired
    BusinessDirectory directory;

    @Autowired
    TenantService tenantService;

    @Autowired
    TenantTransactions tenantTransactions;

    @Autowired
    List<SegmentPack> packs;

    @Autowired
    JdbcClient jdbc;

    private TenantId cantina;
    private TenantId burger;

    @BeforeEach
    void twoTenants() {
        jdbc.sql("DELETE FROM outbound_message").update();
        jdbc.sql("DELETE FROM inbound_event").update();
        jdbc.sql("DELETE FROM tenant_channel").update();
        jdbc.sql("DELETE FROM tenant").update();
        cantina = tenantService.create("cantina", "Cantina", "restaurant", """
                {"business": {"name": "Cantina da Praça"}, "fulfillment": {"delivery": true, "pickup": true, "minimumOrder": 30}}""");
        burger = tenantService.create("burger", "Burger", "restaurant", "{\"business\": {\"name\": \"Burger\"}}");
    }

    @Test
    void importsTheMenuInSpreadsheetOrderWithOptions() {
        var imported = importer.importMenu(cantina, MENU, OPTIONS);

        assertThat(imported).isEqualTo(new BusinessDataImporter.MenuImport(2, 3, 1));
        List<CatalogItem> catalog = directory.catalog(cantina);
        assertThat(catalog).extracting(CatalogItem::code).containsExactly("P01", "P02", "B01");
        assertThat(catalog.get(0).price()).isEqualByComparingTo("39.90");
        assertThat(catalog.get(0).options()).singleElement()
                .satisfies(option -> assertThat(option.priceDelta()).isEqualByComparingTo("10"));
        assertThat(catalog.get(1).available()).isFalse();
        assertThat(catalog.get(2).available()).isTrue();
        assertThat(catalog.get(2).description()).isEqualTo("350 ml");
    }

    @Test
    void reimportKeepsItemIdsUpdatesPricesAndRemovesMissingItems() {
        importer.importMenu(cantina, MENU, OPTIONS);
        UUID calabresa = directory.catalog(cantina).getFirst().id();

        importer.importMenu(cantina, """
                code;category;name;price
                B01;Bebidas;Refrigerante Lata;7,00
                P01;Pizzas;Pizza Calabresa;44,90
                """, null);

        List<CatalogItem> catalog = directory.catalog(cantina);
        assertThat(catalog).extracting(CatalogItem::code).containsExactly("B01", "P01");
        assertThat(catalog.get(1).id()).isEqualTo(calabresa);
        assertThat(catalog.get(1).price()).isEqualByComparingTo("44.90");
        assertThat(catalog.get(1).options()).isEmpty();
    }

    @Test
    void anInvalidSpreadsheetChangesNothing() {
        importer.importMenu(cantina, MENU, OPTIONS);

        assertThatThrownBy(() -> importer.importMenu(cantina, """
                code;category;name;price
                P01;Pizzas;Pizza Calabresa;quarenta
                P01;Pizzas;Repetida;10
                X01;;Sem categoria;5
                """, null))
                .isInstanceOfSatisfying(CsvImportException.class, e -> assertThat(e.errors())
                        .anySatisfy(error -> assertThat(error).startsWith("line 2: price"))
                        .anySatisfy(error -> assertThat(error).startsWith("line 3: code 'P01' is repeated"))
                        .anySatisfy(error -> assertThat(error).startsWith("line 4: category is required")));
        assertThatThrownBy(() -> importer.importMenu(cantina, MENU, "item_code,group,name\nZZ9,Tamanho,Grande\n"))
                .isInstanceOfSatisfying(CsvImportException.class,
                        e -> assertThat(e.errors()).singleElement().asString().contains("'ZZ9' is not in menu.csv"));

        assertThat(directory.catalog(cantina)).hasSize(3);
    }

    @Test
    void importsOpeningHoursAndDeliveryZones() {
        int periods = importer.importOpeningHours(cantina, "day,opens,closes\nfri,18:00,02:00\n7,11:00,15:00\n");
        int zones = importer.importDeliveryZones(cantina, "neighborhood;fee\nCentro;5\nJardim Botânico;8,50\n");

        assertThat(periods).isEqualTo(2);
        assertThat(zones).isEqualTo(2);
        var hours = directory.openingHours(cantina);
        assertThat(hours.zone().getId()).isEqualTo("America/Sao_Paulo");
        assertThat(hours.periods()).extracting(period -> period.day()).containsExactly(DayOfWeek.FRIDAY, DayOfWeek.SUNDAY);
        assertThat(hours.periods().getFirst().closes()).isEqualTo(LocalTime.of(2, 0));
        assertThat(directory.deliveryZones(cantina)).extracting(zone -> zone.neighborhood())
                .containsExactly("Centro", "Jardim Botânico");

        assertThatThrownBy(() -> importer.importDeliveryZones(cantina, "neighborhood;fee\nCentro;5\ncentro;6\n"))
                .isInstanceOf(CsvImportException.class);
        assertThatThrownBy(() -> importer.importOpeningHours(cantina, "day,opens,closes\nfunday,18:00,25:00\n"))
                .isInstanceOfSatisfying(CsvImportException.class, e -> assertThat(e.errors()).hasSize(2));
    }

    @Test
    void anotherTenantSeesNoneOfIt() {
        importer.importMenu(cantina, MENU, OPTIONS);
        importer.importOpeningHours(cantina, "day,opens,closes\nfri,18:00,23:00\n");
        importer.importDeliveryZones(cantina, "neighborhood,fee\nCentro,5\n");

        assertThat(directory.catalog(burger)).isEmpty();
        assertThat(directory.openingHours(burger).registered()).isFalse();
        assertThat(directory.deliveryZones(burger)).isEmpty();
        for (String table : List.of("catalog_category", "catalog_item", "catalog_option", "business_hours", "delivery_zone")) {
            Integer visible = tenantTransactions.inTenant(burger,
                    () -> jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single());
            assertThat(visible).as(table).isZero();
        }
    }

    @Test
    void aTenantCannotWriteRowsOfAnotherTenant() {
        assertThatThrownBy(() -> tenantTransactions.runInTenant(burger, () -> jdbc.sql("""
                        INSERT INTO delivery_zone (tenant_id, neighborhood, fee, position) VALUES (:tenant, 'Centro', 1, 0)
                        """)
                .param("tenant", cantina.value())
                .update()))
                .rootCause().hasMessageContaining("violates row-level security policy");
    }

    @Test
    void theRestaurantToolsAnswerFromTheImportedData() {
        importer.importMenu(cantina, MENU, OPTIONS);
        importer.importDeliveryZones(cantina, "neighborhood;fee\nJardim Botânico;8,50\n");
        SegmentPack restaurant = packs.stream().filter(pack -> pack.id().equals("restaurant")).findFirst().orElseThrow();

        JsonNode menu = run(restaurant, "search_menu", cantina, "{\"query\": \"pizza calabresa\"}");
        JsonNode fee = run(restaurant, "get_delivery_fee", cantina, "{\"neighborhood\": \"jardim botanico\"}");
        JsonNode info = run(restaurant, "get_business_info", cantina, "{}");
        JsonNode otherMenu = run(restaurant, "search_menu", burger, "{\"query\": \"pizza calabresa\"}");

        assertThat(menu.path("items").path(0).path("priceFormatted").asString()).isEqualTo("R$ 39,90");
        assertThat(fee.path("feeFormatted").asString()).isEqualTo("R$ 8,50");
        assertThat(fee.path("minimumOrderFormatted").asString()).isEqualTo("R$ 30,00");
        assertThat(info.path("name").asString()).isEqualTo("Cantina da Praça");
        assertThat(otherMenu.path("menuRegistered").asBoolean(true)).isFalse();
    }

    private static JsonNode run(SegmentPack pack, String toolName, TenantId tenant, String input) {
        Tool tool = pack.tools().stream().filter(candidate -> candidate.name().equals(toolName)).findFirst().orElseThrow();
        var result = tool.execute(new ToolInvocation(tenant, "conversation-1", input));
        assertThat(result.error()).isFalse();
        return JsonMapper.builder().build().readTree(result.contentJson());
    }
}
