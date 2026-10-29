package br.com.conversa.app.compose;

import br.com.conversa.app.TestcontainersConfiguration;
import br.com.conversa.app.conversation.ReplyWorker;
import br.com.conversa.app.conversation.ScriptedLlm;
import br.com.conversa.app.inbound.InboundWorker;
import br.com.conversa.app.outbound.FakeGraphApi;
import br.com.conversa.app.outbound.SenderWorker;
import br.com.conversa.app.tenant.TenantChannelService;
import br.com.conversa.app.tenant.TenantService;
import br.com.conversa.core.llm.LlmClient;
import br.com.conversa.core.tenant.TenantId;
import org.junit.jupiter.api.AfterAll;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * H3: an answer is a turn in the outbox — "typing..." at once, each message later by its length —
 * and the sender waits for nothing: it only sends what is due.
 */
@SpringBootTest(properties = {
        "conversa.whatsapp.app-secret=" + HumanPaceIT.APP_SECRET,
        "conversa.whatsapp.access-token=it-platform-token",
        "conversa.workers.enabled=false",
        "conversa.assistant.debounce=0s"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, HumanPaceIT.Llm.class})
@Testcontainers(disabledWithoutDocker = true)
class HumanPaceIT {

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

    @Test
    void messagesWaitForTheirTurnInTheOutbox() throws Exception {
        jdbc.sql("DELETE FROM outbound_message").update();
        jdbc.sql("DELETE FROM inbound_event").update();
        jdbc.sql("DELETE FROM tenant_channel").update();
        jdbc.sql("DELETE FROM tenant").update();
        TenantId tenant = tenantService.create("cantina", "Cantina", "restaurant", """
                {"business": {"name": "Cantina"},
                 "humanization": {"maxBubbles": 2, "typingIndicator": true, "msPerChar": 40, "minDelayMs": 1500,
                                  "maxDelayMs": 5000, "groupingSeconds": 4}}""");
        channels.register(tenant, PHONE_NUMBER_ID, "100000000000001", null);
        llm.then(ScriptedLlm.text("Tem sim!\n---\nQuer pedir?"));

        post("""
                "messages": [{"from": "%s", "id": "wamid.IN1", "timestamp": "%d", "type": "text",
                              "text": {"body": "Tem calabresa?"}}]
                """.formatted(CUSTOMER, Instant.now().getEpochSecond()));
        inboundWorker.runOnce();
        replyWorker.runOnce();

        List<Map<String, Object>> turn = jdbc.sql("""
                        SELECT kind, available_at, turn_seq FROM outbound_message ORDER BY turn_seq
                        """).query().listOfRows();
        assertThat(turn).extracting(row -> row.get("kind")).containsExactly("TYPING", "TEXT", "TYPING", "TEXT");
        Instant typing = at(turn.get(0));
        assertThat(Duration.between(typing, at(turn.get(1)))).isGreaterThanOrEqualTo(Duration.ofMillis(1500));
        assertThat(Duration.between(at(turn.get(1)), at(turn.get(3)))).isGreaterThanOrEqualTo(Duration.ofMillis(1500));

        senderWorker.runOnce();
        assertThat(graph.requestBodies()).singleElement().asString().contains("typing_indicator");

        jdbc.sql("UPDATE outbound_message SET available_at = now() WHERE status = 'PENDING'").update();
        senderWorker.runOnce();
        assertThat(graph.requestBodies()).hasSize(4);
    }

    private static Instant at(Map<String, Object> row) {
        Object value = row.get("available_at");
        return value instanceof OffsetDateTime time ? time.toInstant() : ((java.sql.Timestamp) value).toInstant();
    }

    private void post(String value) throws Exception {
        byte[] body = """
                {"object": "whatsapp_business_account",
                 "entry": [{"id": "100000000000001", "changes": [{"field": "messages", "value": {
                   "messaging_product": "whatsapp",
                   "metadata": {"display_phone_number": "551630000000", "phone_number_id": "%s"},
                   %s}}]}]}
                """.formatted(PHONE_NUMBER_ID, value).getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(APP_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mockMvc.perform(MockMvcRequestBuilders.post("/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=" + HexFormat.of().formatHex(mac.doFinal(body)))
                        .content(body))
                .andExpect(status().isOk());
    }
}
