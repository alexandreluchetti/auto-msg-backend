package br.com.conversa.app.conversation;

import br.com.conversa.app.PrivacyNotices;
import br.com.conversa.app.TestcontainersConfiguration;
import br.com.conversa.app.business.BusinessDataImporter;
import br.com.conversa.app.inbound.InboundWorker;
import br.com.conversa.app.outbound.FakeGraphApi;
import br.com.conversa.app.outbound.SenderWorker;
import br.com.conversa.app.tenant.TenantChannelService;
import br.com.conversa.app.tenant.TenantService;
import br.com.conversa.app.tenant.TenantTransactions;
import br.com.conversa.core.llm.LlmClient;
import br.com.conversa.core.llm.LlmContent;
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
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M5 end to end: webhook → conversation → reply worker (scripted model, real restaurant tools and
 * data) → outbox → Graph API fake, with cost recorded per tenant.
 */
@SpringBootTest(properties = {
        "conversa.whatsapp.app-secret=" + ConversationFlowIT.APP_SECRET,
        "conversa.whatsapp.access-token=it-platform-token",
        "conversa.workers.enabled=false",
        "conversa.assistant.debounce=0s",
        "conversa.assistant.retry-delay=0s"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, ConversationFlowIT.Llm.class})
@Testcontainers(disabledWithoutDocker = true)
class ConversationFlowIT {

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
        jdbc.sql("DELETE FROM tenant_channel").update();
        jdbc.sql("DELETE FROM tenant").update();
        llm.reset();
        graph.reset();
        tenant = tenantService.create("cantina", "Cantina", "restaurant",
                "{\"business\": {\"name\": \"Cantina da Praça\"}, \"persona\": {\"name\": \"Rita\"}}");
        channels.register(tenant, PHONE_NUMBER_ID, "100000000000001", null);
        importer.importMenu(tenant, """
                code;category;name;price
                P01;Pizzas;Pizza Calabresa;39,90
                """, null);
    }

    @Test
    void answersACustomerQuestionWithTheMenuTool() throws Exception {
        llm.then(ScriptedLlm.toolCall("t1", "search_menu", "{\"query\": \"calabresa\"}"))
                .then(ScriptedLlm.text("Temos sim! A Pizza Calabresa sai por R$ 39,90."));

        postWebhook(textMessage("wamid.IN1", "Tem pizza de calabresa?"));
        inboundWorker.runOnce();
        assertThat(replyWorker.runOnce()).isEqualTo(1);
        senderWorker.runOnce();

        assertThat(graph.requestBodies()).hasSize(2);
        assertThat(graph.requestBodies()).anySatisfy(body ->
                assertThat(mapper.readTree(body).path("message_id").asString()).isEqualTo("wamid.IN1"));
        assertThat(graph.requestBodies()).anySatisfy(body -> {
            var json = mapper.readTree(body);
            assertThat(json.path("to").asString()).isEqualTo(CUSTOMER);
            assertThat(PrivacyNotices.strip(json.path("text").path("body").asString()))
                    .isEqualTo("Temos sim! A Pizza Calabresa sai por R$ 39,90.");
        });

        var request = llm.requests().getFirst();
        assertThat(request.system()).contains("Seu nome é Rita", "restaurante Cantina da Praça",
                "Nunca diga nem dê a entender que é uma pessoa");
        assertThat(request.tools()).extracting(spec -> spec.name())
                .containsExactly("search_menu", "get_business_hours", "get_delivery_fee", "get_business_info",
                        "add_to_cart", "update_cart", "view_cart", "set_fulfillment", "set_payment", "start_checkout",
                        "request_human");
        var toolResult = (LlmContent.ToolOutcome) llm.requests().get(1).messages().getLast().content().getFirst();
        assertThat(toolResult.contentJson()).contains("R$ 39,90");

        assertThat(messages()).containsExactly("IN:Tem pizza de calabresa?", "OUT:Temos sim! A Pizza Calabresa sai por R$ 39,90.");
        var cost = jdbc.sql("SELECT count(*) AS calls, sum(cost_usd) AS cost FROM llm_call WHERE tenant_id = :tenant")
                .param("tenant", tenant.value()).query().singleRow();
        assertThat(cost.get("calls")).isEqualTo(2L);
        // 2 x (1000 in) + 100 + 50 out + 2 x 500 cache reads at Opus 5.5 prices
        assertThat((BigDecimal) cost.get("cost")).isEqualByComparingTo("0.011200");
    }

    @Test
    void aBurstOfMessagesGetsOneAnswerAndRedeliveryChangesNothing() throws Exception {
        llm.then(ScriptedLlm.text("Abrimos às 18h."));

        postWebhook(textMessage("wamid.IN1", "Oi"));
        postWebhook(textMessage("wamid.IN2", "Vocês abrem hoje?"));
        inboundWorker.runOnce();
        replyWorker.runOnce();
        postWebhook(textMessage("wamid.IN2", "Vocês abrem hoje?"));
        inboundWorker.runOnce();
        assertThat(replyWorker.runOnce()).isZero();
        senderWorker.runOnce();

        assertThat(llm.requests()).hasSize(1);
        assertThat(llm.requests().getFirst().messages().getLast().content()).hasSize(3);
        assertThat(graph.requestBodies()).hasSize(2);
        assertThat(messages()).containsExactly("IN:Oi", "IN:Vocês abrem hoje?", "OUT:Abrimos às 18h.");
    }

    @Test
    void temporaryModelFailuresAreRetriedThenAnswered() throws Exception {
        llm.thenFail(true).then(ScriptedLlm.text("Oi! Como posso ajudar?"));

        postWebhook(textMessage("wamid.IN1", "Oi"));
        inboundWorker.runOnce();
        replyWorker.runOnce();
        senderWorker.runOnce();

        assertThat(llm.requests()).hasSize(2);
        assertThat(messages()).containsExactly("IN:Oi", "OUT:Oi! Como posso ajudar?");
        assertThat(jdbc.sql("SELECT reply_due_at IS NULL FROM conversation").query(Boolean.class).single()).isTrue();
    }

    @Test
    void audioIsStoredAsAMarkerTheModelUnderstands() throws Exception {
        llm.then(ScriptedLlm.text("Ainda não consigo ouvir áudios. Pode escrever?"));

        postWebhook(notification("""
                "messages": [{"from": "%s", "id": "wamid.AUDIO", "timestamp": "%d", "type": "audio",
                              "audio": {"id": "media-1", "mime_type": "audio/ogg"}}]
                """.formatted(CUSTOMER, Instant.now().getEpochSecond())));
        inboundWorker.runOnce();
        replyWorker.runOnce();

        assertThat(messages().getFirst()).isEqualTo("IN:[áudio]");
    }

    @Test
    void anotherTenantSeesNoConversationData() throws Exception {
        llm.then(ScriptedLlm.text("Oi!"));
        postWebhook(textMessage("wamid.IN1", "Oi"));
        inboundWorker.runOnce();
        replyWorker.runOnce();
        TenantId other = tenantService.create("other", "Other", "restaurant", "{\"business\": {\"name\": \"Other\"}}");

        for (String table : List.of("contact", "conversation", "message", "llm_call")) {
            Integer own = tenantTransactions.inTenant(tenant,
                    () -> jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single());
            Integer foreign = tenantTransactions.inTenant(other,
                    () -> jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single());
            assertThat(own).as(table).isPositive();
            assertThat(foreign).as(table).isZero();
        }
    }

    private List<String> messages() {
        return jdbc.sql("SELECT direction || ':' || text FROM message ORDER BY created_at")
                .query(String.class).list().stream().map(PrivacyNotices::strip).toList();
    }

    private void postWebhook(byte[] body) throws Exception {
        mockMvc.perform(post("/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", sign(body))
                        .content(body))
                .andExpect(status().isOk());
    }

    private static byte[] textMessage(String wamid, String text) {
        return notification("""
                "contacts": [{"profile": {"name": "Cliente Teste"}, "wa_id": "%s"}],
                "messages": [{"from": "%s", "id": "%s", "timestamp": "%d", "type": "text",
                              "text": {"body": "%s"}}]
                """.formatted(CUSTOMER, CUSTOMER, wamid, Instant.now().getEpochSecond(), text));
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
