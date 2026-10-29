package br.com.conversa.app.order;

import br.com.conversa.app.PrivacyNotices;
import br.com.conversa.app.TestcontainersConfiguration;
import br.com.conversa.app.business.BusinessDataImporter;
import br.com.conversa.app.conversation.ReplyWorker;
import br.com.conversa.app.conversation.ScriptedLlm;
import br.com.conversa.app.inbound.InboundWorker;
import br.com.conversa.app.outbound.FakeGraphApi;
import br.com.conversa.app.outbound.SenderWorker;
import br.com.conversa.app.tenant.TenantChannelService;
import br.com.conversa.app.tenant.TenantService;
import br.com.conversa.app.tenant.TenantTransactions;
import br.com.conversa.core.llm.LlmClient;
import br.com.conversa.core.llm.LlmContent;
import br.com.conversa.core.llm.LlmResponse;
import br.com.conversa.core.llm.LlmUsage;
import br.com.conversa.core.tenant.TenantId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M7 acceptance: the assistant fills the cart with the restaurant's tools, the customer gets a
 * summary priced from the database with a confirmation button, and only the tap creates the
 * order, once.
 */
@SpringBootTest(properties = {
        "conversa.whatsapp.app-secret=" + OrderFlowIT.APP_SECRET,
        "conversa.whatsapp.access-token=it-platform-token",
        "conversa.workers.enabled=false",
        "conversa.assistant.debounce=0s",
        "conversa.assistant.pace-scale=0",
        "conversa.orders.staff-alert-template=novo_pedido"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, OrderFlowIT.Llm.class})
@Testcontainers(disabledWithoutDocker = true)
class OrderFlowIT {

    static final String APP_SECRET = "it-app-secret";
    private static final String PHONE_NUMBER_ID = "200000000000001";
    private static final String CUSTOMER = "5516990000001";
    private static final String STAFF = "5516991110000";
    private static final ScriptedLlm llm = new ScriptedLlm();
    private static final FakeGraphApi graph = new FakeGraphApi();

    @TestConfiguration(proxyBeanMethods = false)
    static class Llm {
        @Bean
        @Primary
        LlmClient scriptedLlm() {
            return llm;
        }
    }

    @DynamicPropertySource
    static void graphApi(DynamicPropertyRegistry registry) {
        registry.add("conversa.whatsapp.graph-api-url", graph::baseUrl);
    }

    @AfterAll
    static void stopGraphApi() {
        graph.close();
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    InboundWorker inboundWorker;

    @Autowired
    ReplyWorker replyWorker;

    @Autowired
    SenderWorker senderWorker;

    @Autowired
    TenantService tenantService;

    @Autowired
    TenantChannelService channels;

    @Autowired
    TenantTransactions tenantTransactions;

    @Autowired
    BusinessDataImporter importer;

    @Autowired
    ObjectMapper mapper;

    private TenantId tenant;

    @BeforeEach
    void pilotRestaurant() {
        jdbc.sql("DELETE FROM outbound_message").update();
        jdbc.sql("DELETE FROM inbound_event").update();
        jdbc.sql("DELETE FROM customer_order").update();
        jdbc.sql("DELETE FROM tenant_channel").update();
        jdbc.sql("DELETE FROM tenant").update();
        llm.reset();
        graph.reset();
        tenant = tenantService.create("cantina", "Cantina", "restaurant", """
                {"business": {"name": "Cantina da Praça"}, "staffNumbers": ["%s"],
                 "fulfillment": {"delivery": true, "pickup": true, "minimumOrder": 30},
                 "paymentMethods": ["cash", "pix_on_delivery"]}
                """.formatted(STAFF));
        channels.register(tenant, PHONE_NUMBER_ID, "100000000000001", null);
        importer.importMenu(tenant, """
                code;category;name;price
                P01;Pizzas;Pizza Calabresa;49,90
                B01;Bebidas;Refrigerante Lata;6,50
                """, """
                item_code;group;name;price_delta
                P01;Tamanho;Grande;12,00
                """);
        importer.importDeliveryZones(tenant, "neighborhood;fee\nCentro;5,00\n");
    }

    @Test
    void onlyTheTapCreatesTheOrderExactlyAsSummarized() throws Exception {
        String checkout = orderUpToTheSummary();

        assertThat(orders()).isEmpty();
        JsonNode summary = lastButtons();
        assertThat(summary.path("interactive").path("body").path("text").asString()).contains(
                "1x Pizza Calabresa (Tamanho: Grande) — R$ 61,90", "2x Refrigerante Lata — R$ 13,00",
                "Taxa de entrega (Centro): R$ 5,00", "*Total: R$ 79,90*");
        assertThat(summary.path("interactive").path("action").path("buttons").path(1).path("reply").path("id").asString())
                .isEqualTo("edit:" + checkout);

        tap("wamid.TAP1", "confirm:" + checkout);

        assertThat(orders()).singleElement().satisfies(order -> {
            assertThat(order.get("number")).isEqualTo(1);
            assertThat(order.get("confirmation_wamid")).isEqualTo("wamid.TAP1");
            assertThat(order.get("fulfillment")).isEqualTo("DELIVERY");
            assertThat(order.get("neighborhood")).isEqualTo("Centro");
            assertThat(order.get("payment_method")).isEqualTo("cash");
            assertThat((BigDecimal) order.get("change_for")).isEqualByComparingTo("100");
            assertThat((BigDecimal) order.get("total")).isEqualByComparingTo("79.90");
        });
        BigDecimal fromDatabase = jdbc.sql("""
                        SELECT sum(oi.quantity * (ci.price + coalesce((SELECT sum(co.price_delta) FROM catalog_option co
                                   WHERE co.item_id = ci.id AND oi.options::text LIKE '%' || co.name || '%'), 0)))
                        FROM order_item oi JOIN catalog_item ci ON ci.id = oi.catalog_item_id
                        """)
                .query(BigDecimal.class).single();
        assertThat(fromDatabase.add(new BigDecimal("5.00"))).isEqualByComparingTo("79.90");
        assertThat(jdbc.sql("SELECT name || ' x' || quantity || ' ' || line_total FROM order_item ORDER BY position")
                .query(String.class).list()).containsExactly("Pizza Calabresa x1 61.90", "Refrigerante Lata x2 13.00");
        assertThat(lastText()).startsWith("Pedido *#1* confirmado!").contains("R$ 79,90", "Rua A, 1 - Centro");
        JsonNode alert = staffAlert();
        assertThat(alert.path("template").path("name").asString()).isEqualTo("novo_pedido");
        assertThat(alert.path("template").path("components").path(0).path("parameters").valueStream()
                .map(parameter -> parameter.path("text").asString()).toList())
                .containsExactly("#1", "Cliente Teste (final 0001)", "Entrega - Centro", "R$ 79,90");
        assertThat(jdbc.sql("SELECT context::text FROM conversation").query(String.class).single()).isEqualTo("{}");
    }

    @Test
    void aSecondTapCreatesNoSecondOrder() throws Exception {
        String checkout = orderUpToTheSummary();

        tap("wamid.TAP1", "confirm:" + checkout);
        tap("wamid.TAP2", "confirm:" + checkout);

        assertThat(orders()).hasSize(1);
        assertThat(lastText()).isEqualTo("Seu pedido *#1* já está confirmado e a equipe já recebeu.");
    }

    @Test
    void aTapOnAnOutdatedSummaryCreatesNothing() throws Exception {
        String checkout = orderUpToTheSummary();
        llm.then(toolCalls(call("a1", "add_to_cart", "{\"code\": \"B01\"}")))
                .then(ScriptedLlm.text("Adicionei mais um refrigerante. Quer ver o resumo de novo?"));
        customerSays("wamid.IN2", "Coloca mais um refri");

        tap("wamid.TAP1", "confirm:" + checkout);

        assertThat(orders()).isEmpty();
        assertThat(lastText()).isEqualTo(OrderService.OUTDATED_TEXT);
    }

    @Test
    void typingIsNotConfirming() throws Exception {
        orderUpToTheSummary();
        llm.then(ScriptedLlm.text("Para confirmar, toque no botão *Confirmar pedido* do resumo."));

        customerSays("wamid.IN2", "confirmo");

        assertThat(orders()).isEmpty();
    }

    @Test
    void anotherTenantSeesNoOrders() throws Exception {
        tap("wamid.TAP1", "confirm:" + orderUpToTheSummary());
        TenantId other = tenantService.create("other", "Other", "restaurant", "{\"business\": {\"name\": \"Other\"}}");

        for (String table : List.of("checkout", "customer_order", "order_item", "order_sequence")) {
            Integer foreign = tenantTransactions.inTenant(other,
                    () -> jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single());
            assertThat(foreign).as(table).isZero();
        }
    }

    /** The assistant fills the cart and asks for the summary; returns the checkout id. */
    private String orderUpToTheSummary() throws Exception {
        llm.then(toolCalls(
                call("c1", "add_to_cart", "{\"code\": \"P01\", \"options\": [\"grande\"]}"),
                call("c2", "add_to_cart", "{\"code\": \"B01\", \"quantity\": 2}"),
                call("c3", "set_fulfillment", "{\"type\": \"delivery\", \"address\": \"Rua A, 1\", \"neighborhood\": \"centro\"}"),
                call("c4", "set_payment", "{\"method\": \"cash\", \"changeFor\": 100}"),
                call("c5", "start_checkout", "{}")));
        customerSays("wamid.IN1", "Uma calabresa grande e 2 refri lata, entrega na Rua A, 1, Centro. Dinheiro, troco pra 100");
        return lastButtons().path("interactive").path("action").path("buttons").path(0).path("reply").path("id").asString()
                .substring("confirm:".length());
    }

    private static LlmResponse toolCalls(LlmContent... calls) {
        return new LlmResponse("claude-opus-5-5", List.of(calls), LlmResponse.StopReason.TOOL_USE,
                new LlmUsage(100, 10, 0, 0), null);
    }

    private static LlmContent call(String id, String name, String input) {
        return new LlmContent.ToolCall(id, name, input);
    }

    private void customerSays(String wamid, String text) throws Exception {
        post(notification("""
                "contacts": [{"profile": {"name": "Cliente Teste"}, "wa_id": "%s"}],
                "messages": [{"from": "%s", "id": "%s", "timestamp": "%d", "type": "text", "text": {"body": "%s"}}]
                """.formatted(CUSTOMER, CUSTOMER, wamid, Instant.now().getEpochSecond(), text)));
        processAll();
    }

    private void tap(String wamid, String buttonId) throws Exception {
        post(notification("""
                "contacts": [{"profile": {"name": "Cliente Teste"}, "wa_id": "%s"}],
                "messages": [{"from": "%s", "id": "%s", "timestamp": "%d", "type": "interactive",
                              "interactive": {"type": "button_reply",
                                              "button_reply": {"id": "%s", "title": "Confirmar pedido"}}}]
                """.formatted(CUSTOMER, CUSTOMER, wamid, Instant.now().getEpochSecond(), buttonId)));
        processAll();
    }

    private void processAll() {
        inboundWorker.runOnce();
        replyWorker.runOnce();
        senderWorker.runOnce();
    }

    private List<Map<String, Object>> orders() {
        return jdbc.sql("""
                        SELECT number, confirmation_wamid, fulfillment, neighborhood, payment_method, change_for, total
                        FROM customer_order ORDER BY number
                        """)
                .query().listOfRows();
    }

    private List<JsonNode> sent() {
        return graph.requestBodies().stream().map(mapper::readTree).toList();
    }

    private JsonNode lastButtons() {
        return sent().stream().filter(body -> "interactive".equals(body.path("type").asString()))
                .reduce((first, second) -> second).orElseThrow();
    }

    private String lastText() {
        return sent().stream()
                .filter(body -> "text".equals(body.path("type").asString()) && CUSTOMER.equals(body.path("to").asString()))
                .map(body -> PrivacyNotices.strip(body.path("text").path("body").asString()))
                .filter(text -> !text.isEmpty())
                .reduce((first, second) -> second).orElseThrow();
    }

    private JsonNode staffAlert() {
        return sent().stream().filter(body -> STAFF.equals(body.path("to").asString())).findFirst().orElseThrow();
    }

    private void post(byte[] body) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", sign(body))
                        .content(body))
                .andExpect(status().isOk());
    }

    private static byte[] notification(String value) {
        return """
                {"object": "whatsapp_business_account",
                 "entry": [{"id": "100000000000001", "changes": [{"field": "messages", "value": {
                   "messaging_product": "whatsapp",
                   "metadata": {"display_phone_number": "551630000000", "phone_number_id": "%s"},
                   %s}}]}]}
                """.formatted(PHONE_NUMBER_ID, value).getBytes(StandardCharsets.UTF_8);
    }

    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(APP_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}
