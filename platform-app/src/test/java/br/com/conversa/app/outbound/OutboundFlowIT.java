package br.com.conversa.app.outbound;

import br.com.conversa.app.TestcontainersConfiguration;
import br.com.conversa.app.inbound.InboundWorker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M2 acceptance: webhook → inbound worker → outbox → sender → Graph API (a local fake), with the
 * 24h window, retries and delivery statuses.
 */
@SpringBootTest(properties = {
        "conversa.whatsapp.app-secret=" + OutboundFlowIT.APP_SECRET,
        "conversa.whatsapp.access-token=it-access-token",
        "conversa.echo-bot.enabled=true",
        "conversa.workers.enabled=false",
        "conversa.workers.max-attempts=2",
        "conversa.workers.retry-base-delay=0s"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class OutboundFlowIT {

    static final String APP_SECRET = "it-app-secret";
    private static final String PHONE_NUMBER_ID = "200000000000001";
    private static final String CUSTOMER = "5516990000001";
    private static final String OTHER_CUSTOMER = "5516990000002";

    private static final FakeGraphApi graph = new FakeGraphApi();

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
    SenderWorker senderWorker;

    @Autowired
    OutboundMessageRepository outbound;

    @Autowired
    ObjectMapper mapper;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM outbound_message").update();
        jdbc.sql("DELETE FROM inbound_event").update();
        graph.reset();
    }

    @Test
    void echoBotAnswersOnceEvenWhenTheMessageIsRedelivered() throws Exception {
        byte[] webhook = textMessage("wamid.IN0001", CUSTOMER, Instant.now(), "Vocês abrem hoje?");

        postWebhook(webhook);
        inboundWorker.runOnce();
        postWebhook(webhook);
        inboundWorker.runOnce();

        assertThat(senderWorker.runOnce()).isEqualTo(2);
        assertThat(senderWorker.runOnce()).isZero();

        assertThat(graph.requestBodies()).hasSize(2);
        assertThat(graph.requestBodies()).anySatisfy(body ->
                assertThat(mapper.readTree(body).path("message_id").asString()).isEqualTo("wamid.IN0001"));
        assertThat(graph.requestBodies()).anySatisfy(body -> {
            var json = mapper.readTree(body);
            assertThat(json.path("to").asString()).isEqualTo(CUSTOMER);
            assertThat(json.path("text").path("body").asString()).isEqualTo("Recebemos: Vocês abrem hoje?");
        });
        assertThat(rows()).extracting(row -> row.get("kind") + ":" + row.get("status"))
                .containsExactly("READ:SENT", "TEXT:SENT");
        assertThat(rows().get(1).get("wamid")).isEqualTo("wamid.OUT1");
    }

    @Test
    void freeTextOutsideTheWindowFailsButTemplateIsSent() {
        // The customer's last message was 25h ago.
        jdbc.sql("""
                        INSERT INTO inbound_event (event_key, event_type, field, phone_number_id, wa_id, payload, status)
                        VALUES ('msg:wamid.OLD', 'MESSAGE', 'messages', :phone, :waId,
                                CAST(:payload AS jsonb), 'PROCESSED')
                        """)
                .param("phone", PHONE_NUMBER_ID)
                .param("waId", CUSTOMER)
                .param("payload", "{\"item\":{\"timestamp\":\""
                        + Instant.now().minus(Duration.ofHours(25)).getEpochSecond() + "\"}}")
                .update();
        outbound.enqueue("t1", OutboundRequest.text(PHONE_NUMBER_ID, CUSTOMER, "Seu pedido saiu!", mapper));
        outbound.enqueue("t2", OutboundRequest.text(PHONE_NUMBER_ID, OTHER_CUSTOMER, "Oi!", mapper));
        outbound.enqueue("t3", OutboundRequest.template(PHONE_NUMBER_ID, CUSTOMER, "pedido_saiu_para_entrega",
                "pt_BR", List.of("Ana"), mapper));

        senderWorker.runOnce();

        assertThat(rows()).extracting(row -> row.get("kind") + ":" + row.get("status"))
                .containsExactly("TEXT:FAILED", "TEXT:FAILED", "TEMPLATE:SENT");
        assertThat((String) rows().getFirst().get("last_error")).startsWith("WINDOW_CLOSED");
        assertThat(graph.requestBodies()).singleElement().satisfies(body ->
                assertThat(mapper.readTree(body).path("type").asString()).isEqualTo("template"));
    }

    @Test
    void retryableFailureIsRetriedUntilMaxAttempts() {
        outbound.enqueue("r1", OutboundRequest.template(PHONE_NUMBER_ID, CUSTOMER, "aviso", "pt_BR", List.of(),
                mapper));
        graph.failWith(503, "");

        senderWorker.runOnce();
        assertThat(rows().getFirst()).containsEntry("status", "PENDING").containsEntry("attempts", 1)
                .containsEntry("last_error", "HTTP_503: HTTP 503");

        senderWorker.runOnce();
        assertThat(rows().getFirst()).containsEntry("status", "FAILED").containsEntry("attempts", 2);
        assertThat(graph.requestBodies()).hasSize(2);
    }

    @Test
    void retryableFailureSucceedsOnTheNextAttempt() {
        outbound.enqueue("r2", OutboundRequest.template(PHONE_NUMBER_ID, CUSTOMER, "aviso", "pt_BR", List.of(),
                mapper));
        graph.failWith(400, "{\"error\":{\"message\":\"Rate limit hit\",\"code\":130429}}");
        senderWorker.runOnce();

        graph.succeed();
        senderWorker.runOnce();

        assertThat(rows().getFirst()).containsEntry("status", "SENT").containsEntry("attempts", 2);
    }

    @Test
    void permanentFailureIsNotRetried() {
        outbound.enqueue("p1", OutboundRequest.template(PHONE_NUMBER_ID, CUSTOMER, "inexistente", "pt_BR",
                List.of(), mapper));
        graph.failWith(400, "{\"error\":{\"message\":\"Template name does not exist\",\"code\":132001}}");

        senderWorker.runOnce();

        assertThat(rows().getFirst()).containsEntry("status", "FAILED").containsEntry("attempts", 1)
                .containsEntry("last_error", "132001: Template name does not exist");
    }

    @Test
    void statusWebhooksUpdateDeliveryAndOnlyMoveForward() throws Exception {
        outbound.enqueue("s1", OutboundRequest.template(PHONE_NUMBER_ID, CUSTOMER, "aviso", "pt_BR", List.of(),
                mapper));
        senderWorker.runOnce();
        String wamid = (String) rows().getFirst().get("wamid");

        postWebhook(statusUpdate(wamid, "delivered"));
        postWebhook(statusUpdate(wamid, "sent"));
        inboundWorker.runOnce();

        assertThat(rows().getFirst())
                .containsEntry("delivery_status", "delivered")
                .containsEntry("pricing_category", "utility")
                .containsEntry("billable", true);
        assertThat(jdbc.sql("SELECT count(*) FROM inbound_event WHERE status = 'PROCESSED'")
                .query(Long.class).single()).isEqualTo(2);
    }

    private List<Map<String, Object>> rows() {
        return jdbc.sql("""
                        SELECT kind, status, attempts, wamid, last_error, delivery_status, pricing_category, billable
                        FROM outbound_message ORDER BY id
                        """)
                .query().listOfRows();
    }

    private void postWebhook(byte[] body) throws Exception {
        mockMvc.perform(post("/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", sign(body))
                        .content(body))
                .andExpect(status().isOk());
    }

    private static byte[] textMessage(String wamid, String from, Instant sentAt, String text) {
        return notification("""
                "contacts": [{"profile": {"name": "Cliente Teste"}, "wa_id": "%s"}],
                "messages": [{"from": "%s", "id": "%s", "timestamp": "%d", "type": "text",
                              "text": {"body": "%s"}}]
                """.formatted(from, from, wamid, sentAt.getEpochSecond(), text));
    }

    private static byte[] statusUpdate(String wamid, String status) {
        return notification("""
                "statuses": [{"id": "%s", "status": "%s", "timestamp": "%d", "recipient_id": "%s",
                              "pricing": {"billable": true, "pricing_model": "PMP", "category": "utility"}}]
                """.formatted(wamid, status, Instant.now().getEpochSecond(), CUSTOMER));
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
