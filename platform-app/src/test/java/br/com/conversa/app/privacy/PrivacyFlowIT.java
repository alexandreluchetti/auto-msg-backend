package br.com.conversa.app.privacy;

import br.com.conversa.app.PrivacyNotices;
import br.com.conversa.app.TestcontainersConfiguration;
import br.com.conversa.app.conversation.ReplyWorker;
import br.com.conversa.app.conversation.ScriptedLlm;
import br.com.conversa.app.inbound.InboundWorker;
import br.com.conversa.app.order.OrderService;
import br.com.conversa.app.outbound.FakeGraphApi;
import br.com.conversa.app.outbound.SenderWorker;
import br.com.conversa.app.tenant.TenantChannelService;
import br.com.conversa.app.tenant.TenantService;
import br.com.conversa.app.tenant.TenantTransactions;
import br.com.conversa.core.llm.LlmClient;
import br.com.conversa.core.order.OrderDraft;
import br.com.conversa.core.order.OrderLine;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M9 acceptance: the first answer carries the privacy notice, an erasure request anonymizes the
 * customer's data after confirmation, retention cleans old data, and business metrics are exported.
 */
@SpringBootTest(properties = {
        "conversa.whatsapp.app-secret=" + PrivacyFlowIT.APP_SECRET,
        "conversa.whatsapp.access-token=it-platform-token",
        "conversa.workers.enabled=false",
        "conversa.assistant.debounce=0s",
        "conversa.assistant.pace-scale=0"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, PrivacyFlowIT.Llm.class})
@Testcontainers(disabledWithoutDocker = true)
class PrivacyFlowIT {

    static final String APP_SECRET = "it-app-secret";
    private static final String PHONE_NUMBER_ID = "200000000000001";
    private static final String CUSTOMER = "5516990000001";
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
    PrivacyService privacy;

    @Autowired
    RetentionJob retention;

    @Autowired
    TenantService tenantService;

    @Autowired
    TenantChannelService channels;

    @Autowired
    TenantTransactions tenantTransactions;

    @Autowired
    OrderService orders;

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
                {"business": {"name": "Cantina da Praça"}, "privacy": {"policyUrl": "https://cantina.test/privacidade"}}
                """);
        channels.register(tenant, PHONE_NUMBER_ID, "100000000000001", null);
    }

    @Test
    void theFirstAnswerCarriesThePrivacyNoticeOnce() throws Exception {
        llm.then(ScriptedLlm.text("Oi! Como posso ajudar?")).then(ScriptedLlm.text("Abrimos às 18h."));

        says("wamid.IN1", "Oi");
        says("wamid.IN2", "Que horas abre?");

        List<String> texts = sentTexts();
        assertThat(texts).hasSize(3);
        assertThat(texts.get(0)).startsWith("_Este é o atendimento automático do Cantina da Praça.")
                .contains("política de privacidade: https://cantina.test/privacidade", "\"apagar meus dados\"");
        assertThat(texts.get(1)).isEqualTo("Oi! Como posso ajudar?");
        assertThat(texts.get(2)).isEqualTo("Abrimos às 18h.");
        assertThat(jdbc.sql("SELECT consent_notice_at IS NOT NULL FROM contact").query(Boolean.class).single()).isTrue();
    }

    @Test
    void anErasureRequestAnonymizesTheCustomerAfterConfirmation() throws Exception {
        llm.then(ScriptedLlm.text("Oi! Como posso ajudar?"));
        says("wamid.IN1", "Oi");
        confirmedOrder();

        says("wamid.IN2", "Quero apagar meus dados");
        JsonNode ask = lastButtons();
        assertThat(ask.path("interactive").path("body").path("text").asString()).isEqualTo(PrivacyService.CONFIRM_TEXT);
        String yes = ask.path("interactive").path("action").path("buttons").path(0).path("reply").path("id").asString();
        assertThat(yes).startsWith("erase:yes:");
        tap("wamid.TAP1", yes, "Sim, apagar");

        assertThat(sentTexts().getLast()).isEqualTo(PrivacyService.ERASED_TEXT);
        assertThat(jdbc.sql("SELECT count(*) FROM message").query(Long.class).single()).isZero();
        Map<String, Object> contact = jdbc.sql("SELECT wa_id, display_name, erased_at FROM contact").query().singleRow();
        assertThat((String) contact.get("wa_id")).startsWith("erased:");
        assertThat(contact.get("display_name")).isNull();
        assertThat(contact.get("erased_at")).isNotNull();
        assertThat(jdbc.sql("SELECT address IS NULL FROM customer_order").query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT total FROM customer_order").query(BigDecimal.class).single()).isEqualByComparingTo("54.90");

        assertThat(privacy.purgeQueues()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM inbound_event WHERE wa_id = :wa").param("wa", CUSTOMER)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM outbound_message WHERE wa_id = :wa").param("wa", CUSTOMER)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM inbound_event WHERE payload::text LIKE '%apagar%'")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT status || ' ' || coalesce(wa_id, '-') FROM data_subject_request")
                .query(String.class).single()).isEqualTo("DONE -");

        llm.then(ScriptedLlm.text("Olá! Como posso ajudar?"));
        says("wamid.IN3", "Oi de novo");
        assertThat(jdbc.sql("SELECT count(*) FROM contact WHERE wa_id = :wa").param("wa", CUSTOMER)
                .query(Long.class).single()).isEqualTo(1);
        List<String> afterErasure = sentTexts();
        assertThat(PrivacyNotices.has(afterErasure.get(afterErasure.size() - 2))).isTrue();
    }

    @Test
    void sayingNoKeepsTheData() throws Exception {
        says("wamid.IN1", "apagar meus dados");
        String no = lastButtons().path("interactive").path("action").path("buttons").path(1).path("reply").path("id")
                .asString();

        tap("wamid.TAP1", no, "Não");

        assertThat(sentTexts().getLast()).isEqualTo(PrivacyService.KEPT_TEXT);
        assertThat(jdbc.sql("SELECT wa_id FROM contact").query(String.class).single()).isEqualTo(CUSTOMER);
        assertThat(jdbc.sql("SELECT status FROM data_subject_request").query(String.class).single()).isEqualTo("CANCELLED");
    }

    @Test
    void retentionRemovesOldMessagesAndPersonalDataOfOldOrders() throws Exception {
        llm.then(ScriptedLlm.text("Oi!"));
        says("wamid.IN1", "Oi");
        confirmedOrder();
        jdbc.sql("UPDATE message SET created_at = now() - interval '200 days'").update();
        jdbc.sql("UPDATE customer_order SET created_at = now() - interval '200 days'").update();
        jdbc.sql("UPDATE contact SET created_at = now() - interval '200 days'").update();
        jdbc.sql("UPDATE inbound_event SET received_at = now() - interval '200 days'").update();
        jdbc.sql("UPDATE outbound_message SET created_at = now() - interval '200 days'").update();

        RetentionJob.Result result = retention.runOnce();

        assertThat(result.messages()).isEqualTo(2);
        assertThat(result.orders()).isEqualTo(1);
        assertThat(result.contacts()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM message").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT address IS NULL FROM customer_order").query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM inbound_event").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM customer_order").query(Long.class).single()).isEqualTo(1);
        assertThat(retention.runOnce()).isEqualTo(new RetentionJob.Result(0, 0, 0, 0));
    }

    @Test
    void businessMetricsAreExported() throws Exception {
        llm.then(ScriptedLlm.text("Oi!"));
        says("wamid.IN1", "Oi");
        confirmedOrder();

        String metrics = mockMvc.perform(MockMvcRequestBuilders.get("/actuator/prometheus"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(metrics).contains("conversa_llm_cost_usd_total{model=\"claude-opus-5-5\",tenant=\"cantina\"}",
                "conversa_llm_tokens_total{", "conversa_orders_total{tenant=\"cantina\"}",
                "conversa_orders_value_brl_total{tenant=\"cantina\"}", "conversa_queue_inbound_lag_seconds",
                "conversa_queue_outbound_pending", "conversa_conversations_human");
    }

    private void confirmedOrder() {
        UUID conversation = jdbc.sql("SELECT id FROM conversation").query(UUID.class).single();
        OrderDraft draft = new OrderDraft("DELIVERY", "Rua A, 1", "Centro", new BigDecimal("5.00"), "cash", null,
                List.of(new OrderLine(null, "P01", "Pizza Calabresa", 1, new BigDecimal("49.90"), List.of(), "sem cebola",
                        new BigDecimal("49.90"))),
                new BigDecimal("49.90"), new BigDecimal("54.90"));
        tenantTransactions.runInTenant(tenant, () -> {
            UUID checkout = orders.openCheckout(tenant, conversation, draft);
            orders.confirm(tenant, conversation, checkout.toString(), "wamid.ORDER", PHONE_NUMBER_ID, "Cliente",
                    mapper.createObjectNode());
        });
    }

    private void says(String wamid, String text) throws Exception {
        post("""
                "contacts": [{"profile": {"name": "Cliente Teste"}, "wa_id": "%s"}],
                "messages": [{"from": "%s", "id": "%s", "timestamp": "%d", "type": "text", "text": {"body": "%s"}}]
                """.formatted(CUSTOMER, CUSTOMER, wamid, Instant.now().getEpochSecond(), text));
    }

    private void tap(String wamid, String id, String title) throws Exception {
        post("""
                "contacts": [{"profile": {"name": "Cliente Teste"}, "wa_id": "%s"}],
                "messages": [{"from": "%s", "id": "%s", "timestamp": "%d", "type": "interactive",
                              "interactive": {"type": "button_reply", "button_reply": {"id": "%s", "title": "%s"}}}]
                """.formatted(CUSTOMER, CUSTOMER, wamid, Instant.now().getEpochSecond(), id, title));
    }

    private void post(String value) throws Exception {
        byte[] body = """
                {"object": "whatsapp_business_account",
                 "entry": [{"id": "100000000000001", "changes": [{"field": "messages", "value": {
                   "messaging_product": "whatsapp",
                   "metadata": {"display_phone_number": "551630000000", "phone_number_id": "%s"},
                   %s}}]}]}
                """.formatted(PHONE_NUMBER_ID, value).getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(MockMvcRequestBuilders.post("/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", sign(body))
                        .content(body))
                .andExpect(status().isOk());
        inboundWorker.runOnce();
        replyWorker.runOnce();
        senderWorker.runOnce();
    }

    private List<String> sentTexts() {
        return graph.requestBodies().stream().map(mapper::readTree)
                .filter(body -> "text".equals(body.path("type").asString()))
                .map(body -> body.path("text").path("body").asString())
                .toList();
    }

    private JsonNode lastButtons() {
        return graph.requestBodies().stream().map(mapper::readTree)
                .filter(body -> "interactive".equals(body.path("type").asString()))
                .reduce((first, second) -> second).orElseThrow();
    }

    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(APP_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}
