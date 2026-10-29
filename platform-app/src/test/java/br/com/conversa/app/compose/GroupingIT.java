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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * H4: the bot waits for the business's silence window, and a customer who writes again while it is
 * still "typing" gets one rebuilt answer instead of the stale messages.
 */
@SpringBootTest(properties = {
        "conversa.whatsapp.app-secret=" + GroupingIT.APP_SECRET,
        "conversa.whatsapp.access-token=it-platform-token",
        "conversa.workers.enabled=false"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, GroupingIT.Llm.class})
@Testcontainers(disabledWithoutDocker = true)
class GroupingIT {

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

    @BeforeEach
    void cantina() {
        jdbc.sql("DELETE FROM outbound_message").update();
        jdbc.sql("DELETE FROM inbound_event").update();
        jdbc.sql("DELETE FROM tenant_channel").update();
        jdbc.sql("DELETE FROM tenant").update();
        llm.reset();
        graph.reset();
        TenantId tenant = tenantService.create("cantina", "Cantina", "restaurant", """
                {"business": {"name": "Cantina"},
                 "humanization": {"maxBubbles": 2, "typingIndicator": true, "msPerChar": 40, "minDelayMs": 1500,
                                  "maxDelayMs": 5000, "groupingSeconds": 7}}""");
        channels.register(tenant, PHONE_NUMBER_ID, "100000000000001", null);
    }

    @Test
    void theBotWaitsForTheBusinessSilenceWindow() throws Exception {
        says("wamid.IN1", "Oi");

        double wait = jdbc.sql("SELECT extract(epoch FROM reply_due_at - now()) FROM conversation")
                .query(Double.class).single();
        assertThat(wait).isBetween(5.5, 7.5);
        assertThat(replyWorker.runOnce()).isZero();
    }

    @Test
    void aMessageWhileTypingCancelsThePendingMessagesAndTheAnswerIsRebuilt() throws Exception {
        llm.then(ScriptedLlm.text("Oi! Tudo bem?\n---\nComo posso ajudar?"))
                .then(ScriptedLlm.text("Temos sim! A calabresa sai por R$ 39,90."));
        says("wamid.IN1", "Oi");
        answerNow();
        senderWorker.runOnce();
        assertThat(sentKinds()).containsExactly("typing");

        says("wamid.IN2", "tem calabresa?");

        assertThat(jdbc.sql("SELECT count(*) FROM outbound_message WHERE status = 'PENDING'").query(Long.class).single())
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM message WHERE direction = 'OUT'").query(Long.class).single()).isZero();
        answerNow();
        jdbc.sql("UPDATE outbound_message SET available_at = now() WHERE status = 'PENDING'").update();
        senderWorker.runOnce();

        List<LlmContent> lastTurn = llm.requests().get(1).messages().getLast().content();
        assertThat(lastTurn).hasSize(3);
        assertThat(lastTurn.get(1)).isEqualTo(new LlmContent.Text("Oi"));
        assertThat(lastTurn.get(2)).isEqualTo(new LlmContent.Text("tem calabresa?"));
        assertThat(sentKinds()).doesNotContain("Oi! Tudo bem?", "Como posso ajudar?").contains("typing");
        assertThat(sentKinds().getLast()).endsWith("Temos sim! A calabresa sai por R$ 39,90.");
    }

    @Test
    void whatWasAlreadySentStaysInTheHistory() throws Exception {
        llm.then(ScriptedLlm.text("Oi! Tudo bem?\n---\nComo posso ajudar?"));
        says("wamid.IN1", "Oi");
        answerNow();
        jdbc.sql("UPDATE outbound_message SET available_at = now() WHERE turn_seq <= 1").update();
        senderWorker.runOnce();

        says("wamid.IN2", "quero pedir");

        assertThat(jdbc.sql("SELECT text FROM message WHERE direction = 'OUT'").query(String.class).single())
                .endsWith("Oi! Tudo bem?").doesNotContain("Como posso ajudar?");
        assertThat(jdbc.sql("SELECT count(*) FROM outbound_message WHERE status = 'CANCELLED' AND kind = 'TEXT'")
                .query(Long.class).single()).isEqualTo(1);
    }

    private void answerNow() {
        jdbc.sql("UPDATE conversation SET reply_due_at = now() WHERE reply_due_at IS NOT NULL").update();
        replyWorker.runOnce();
    }

    private List<String> sentKinds() {
        return graph.requestBodies().stream().map(body -> body.contains("typing_indicator") ? "typing" : body)
                .map(body -> body.startsWith("{") ? body.replaceAll("(?s).*\"body\":\"(.*?)\".*", "$1") : body)
                .toList();
    }

    private void says(String wamid, String text) throws Exception {
        byte[] body = """
                {"object": "whatsapp_business_account",
                 "entry": [{"id": "100000000000001", "changes": [{"field": "messages", "value": {
                   "messaging_product": "whatsapp",
                   "metadata": {"display_phone_number": "551630000000", "phone_number_id": "%s"},
                   "messages": [{"from": "%s", "id": "%s", "timestamp": "%d", "type": "text", "text": {"body": "%s"}}]
                 }}]}]}
                """.formatted(PHONE_NUMBER_ID, CUSTOMER, wamid, Instant.now().getEpochSecond(), text)
                .getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(APP_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mockMvc.perform(MockMvcRequestBuilders.post("/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=" + HexFormat.of().formatHex(mac.doFinal(body)))
                        .content(body))
                .andExpect(status().isOk());
        inboundWorker.runOnce();
    }
}
