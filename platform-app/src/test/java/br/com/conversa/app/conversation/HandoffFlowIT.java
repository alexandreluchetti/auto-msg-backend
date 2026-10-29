package br.com.conversa.app.conversation;

import br.com.conversa.app.PrivacyNotices;
import br.com.conversa.app.TestcontainersConfiguration;
import br.com.conversa.app.inbound.InboundWorker;
import br.com.conversa.app.outbound.FakeGraphApi;
import br.com.conversa.app.outbound.SenderWorker;
import br.com.conversa.app.persona.PersonaTexts;
import br.com.conversa.app.tenant.TenantChannelService;
import br.com.conversa.app.tenant.TenantService;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M6 acceptance: an echo from the app pauses the bot, the pause expiring hands the conversation
 * back, and the customer, the assistant or repeated failures hand it to staff with an alert.
 */
@SpringBootTest(properties = {
        "conversa.whatsapp.app-secret=" + HandoffFlowIT.APP_SECRET,
        "conversa.whatsapp.access-token=it-platform-token",
        "conversa.workers.enabled=false",
        "conversa.assistant.debounce=0s",
        "conversa.assistant.retry-delay=0s",
        "conversa.handoff.staff-alert-template=alerta_atendimento"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, HandoffFlowIT.Llm.class})
@Testcontainers(disabledWithoutDocker = true)
class HandoffFlowIT {

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
        tenant = tenantService.create("cantina", "Cantina", "restaurant", """
                {"business": {"name": "Cantina da Praça"}, "staffNumbers": ["%s"], "handoff": {"pauseMinutes": 30}}
                """.formatted(STAFF));
        channels.register(tenant, PHONE_NUMBER_ID, "100000000000001", null);
    }

    @Test
    void anEchoFromTheAppPausesTheBot() throws Exception {
        postWebhook(textMessage("wamid.IN1", "Oi, quero fazer um pedido"));
        postWebhook(echo("wamid.ECHO1", "Oi! Aqui é a Ana, pode falar."));
        postWebhook(textMessage("wamid.IN2", "Uma calabresa"));
        processAll();

        assertThat(llm.requests()).isEmpty();
        assertThat(graph.requestBodies()).isEmpty();
        assertThat(conversation()).containsEntry("state", "HUMAN").containsEntry("handoff_reason", "STAFF_REPLY");
        assertThat(messages()).containsExactly("IN:CUSTOMER:Oi, quero fazer um pedido",
                "OUT:STAFF:Oi! Aqui é a Ana, pode falar.", "IN:CUSTOMER:Uma calabresa");
        OffsetDateTime pausedUntil = jdbc.sql("SELECT paused_until FROM conversation").query(OffsetDateTime.class).single();
        assertThat(pausedUntil.toInstant()).isBetween(Instant.now().plusSeconds(29 * 60), Instant.now().plusSeconds(31 * 60));
    }

    @Test
    void theBotTakesTheConversationBackAfterThePause() throws Exception {
        postWebhook(textMessage("wamid.IN1", "Oi"));
        postWebhook(echo("wamid.ECHO1", "Oi! Aqui é a Ana."));
        processAll();
        jdbc.sql("UPDATE conversation SET paused_until = now() - interval '1 minute'").update();
        llm.then(ScriptedLlm.text("Olá de novo! Como posso ajudar?"));

        postWebhook(textMessage("wamid.IN2", "Vocês abrem amanhã?"));
        processAll();

        assertThat(conversation()).containsEntry("state", "BOT");
        assertThat(llm.requests()).hasSize(1);
        assertThat(llm.requests().getFirst().messages().get(1).content())
                .containsExactly(new LlmContent.Text("[Mensagem da equipe do restaurante] Oi! Aqui é a Ana."));
        assertThat(sentTexts()).containsExactly("Olá de novo! Como posso ajudar?");
    }

    @Test
    void askingForAPersonHandsOverWithoutCallingTheModel() throws Exception {
        postWebhook(textMessageFrom("Ana Souza", "wamid.IN1", "Quero falar com um atendente"));
        processAll();

        assertThat(llm.requests()).isEmpty();
        assertThat(conversation()).containsEntry("state", "HUMAN").containsEntry("handoff_reason", "CUSTOMER_REQUEST");
        assertThat(sentTexts()).containsExactly(HandoffService.CUSTOMER_NOTICE);
        JsonNode alert = staffAlert();
        assertThat(alert.path("template").path("name").asString()).isEqualTo("alerta_atendimento");
        assertThat(alert.path("template").path("language").path("code").asString()).isEqualTo("pt_BR");
        assertThat(templateParameters(alert)).containsExactly("Ana Souza (final 0001)",
                "o cliente pediu para falar com um atendente");
    }

    @Test
    void theAssistantCanHandOverWithAReason() throws Exception {
        llm.then(ScriptedLlm.toolCall("t1", "request_human", "{\"reason\": \"cliente quer pedir 1 calabresa\"}"));

        postWebhook(textMessage("wamid.IN1", "Me vê uma calabresa pra entrega"));
        processAll();

        assertThat(conversation()).containsEntry("state", "HUMAN").containsEntry("handoff_reason", "BOT_REQUEST");
        assertThat(sentTexts()).containsExactly(HandoffService.CUSTOMER_NOTICE);
        assertThat(templateParameters(staffAlert()).get(1))
                .isEqualTo("o assistente pediu ajuda: cliente quer pedir 1 calabresa");
    }

    @Test
    void repeatedFailuresHandOver() throws Exception {
        llm.thenFail(false).thenFail(false);

        postWebhook(textMessage("wamid.IN1", "Oi"));
        processAll();
        assertThat(conversation()).containsEntry("state", "BOT").containsEntry("failure_streak", 1);
        postWebhook(textMessage("wamid.IN2", "Alô?"));
        processAll();

        assertThat(conversation()).containsEntry("state", "HUMAN").containsEntry("handoff_reason", "REPEATED_FAILURES");
        assertThat(sentTexts()).containsExactly(PersonaTexts.fallback(tenantService.effectiveConfig(tenant)),
                HandoffService.CUSTOMER_NOTICE);
    }

    @Test
    void aNewEchoRenewsThePause() throws Exception {
        postWebhook(echo("wamid.ECHO1", "Oi! Seu pedido saiu."));
        processAll();
        jdbc.sql("UPDATE conversation SET paused_until = now() + interval '1 minute'").update();

        postWebhook(echo("wamid.ECHO2", "Chega em 20 minutos."));
        processAll();

        OffsetDateTime pausedUntil = jdbc.sql("SELECT paused_until FROM conversation").query(OffsetDateTime.class).single();
        assertThat(pausedUntil.toInstant()).isAfter(Instant.now().plusSeconds(29 * 60));
    }

    private void processAll() {
        inboundWorker.runOnce();
        replyWorker.runOnce();
        senderWorker.runOnce();
    }

    private java.util.Map<String, Object> conversation() {
        return jdbc.sql("SELECT state, handoff_reason, paused_until, failure_streak FROM conversation")
                .query().singleRow();
    }

    private List<String> messages() {
        return jdbc.sql("SELECT direction || ':' || author || ':' || text FROM message ORDER BY created_at")
                .query(String.class).list().stream().map(PrivacyNotices::strip).toList();
    }

    private List<String> sentTexts() {
        return graph.requestBodies().stream()
                .map(mapper::readTree)
                .filter(body -> "text".equals(body.path("type").asString()) && CUSTOMER.equals(body.path("to").asString()))
                .map(body -> PrivacyNotices.strip(body.path("text").path("body").asString()))
                .toList();
    }

    private JsonNode staffAlert() {
        return graph.requestBodies().stream()
                .map(mapper::readTree)
                .filter(body -> STAFF.equals(body.path("to").asString()))
                .findFirst().orElseThrow();
    }

    private static List<String> templateParameters(JsonNode alert) {
        return alert.path("template").path("components").path(0).path("parameters").valueStream()
                .map(parameter -> parameter.path("text").asString())
                .toList();
    }

    private void postWebhook(byte[] body) throws Exception {
        mockMvc.perform(post("/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", sign(body))
                        .content(body))
                .andExpect(status().isOk());
    }

    private static byte[] textMessage(String wamid, String text) {
        return textMessageFrom("Cliente Teste", wamid, text);
    }

    private static byte[] textMessageFrom(String name, String wamid, String text) {
        return notification("messages", """
                "contacts": [{"profile": {"name": "%s"}, "wa_id": "%s"}],
                "messages": [{"from": "%s", "id": "%s", "timestamp": "%d", "type": "text",
                              "text": {"body": "%s"}}]
                """.formatted(name, CUSTOMER, CUSTOMER, wamid, Instant.now().getEpochSecond(), text));
    }

    private static byte[] echo(String wamid, String text) {
        return notification("smb_message_echoes", """
                "message_echoes": [{"from": "551630000000", "to": "%s", "id": "%s", "timestamp": "%d",
                                    "type": "text", "text": {"body": "%s"}}]
                """.formatted(CUSTOMER, wamid, Instant.now().getEpochSecond(), text));
    }

    private static byte[] notification(String field, String value) {
        return """
                {"object": "whatsapp_business_account",
                 "entry": [{"id": "100000000000001", "changes": [{"field": "%s", "value": {
                   "messaging_product": "whatsapp",
                   "metadata": {"display_phone_number": "551630000000", "phone_number_id": "%s"},
                   %s}}]}]}
                """.formatted(field, PHONE_NUMBER_ID, value).getBytes(StandardCharsets.UTF_8);
    }

    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(APP_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}
