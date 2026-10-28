package br.com.conversa.app.webhook;

import br.com.conversa.app.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "conversa.whatsapp.app-secret=" + WhatsAppWebhookIT.APP_SECRET,
        "conversa.whatsapp.verify-token=" + WhatsAppWebhookIT.VERIFY_TOKEN,
        "conversa.workers.enabled=false"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class WhatsAppWebhookIT {

    static final String APP_SECRET = "it-app-secret";
    static final String VERIFY_TOKEN = "it-verify-token";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void cleanQueue() {
        jdbc.sql("DELETE FROM inbound_event").update();
    }

    @Test
    void answersVerificationChallengeWithConfiguredToken() throws Exception {
        mockMvc.perform(get("/webhooks/whatsapp")
                        .param("hub.mode", "subscribe")
                        .param("hub.verify_token", VERIFY_TOKEN)
                        .param("hub.challenge", "1158201444"))
                .andExpect(status().isOk())
                .andExpect(content().string("1158201444"));
    }

    @Test
    void refusesVerificationWithWrongToken() throws Exception {
        mockMvc.perform(get("/webhooks/whatsapp")
                        .param("hub.mode", "subscribe")
                        .param("hub.verify_token", "wrong")
                        .param("hub.challenge", "1158201444"))
                .andExpect(status().isForbidden());
    }

    @Test
    void storesEachEventOnceEvenWhenMetaRedelivers() throws Exception {
        byte[] body = fixture("batch-messages.json");

        postSigned(body, sign(body, APP_SECRET)).andExpect(status().isOk());
        postSigned(body, sign(body, APP_SECRET)).andExpect(status().isOk());

        List<String> keys = jdbc.sql("SELECT event_key FROM inbound_event ORDER BY id")
                .query(String.class).list();
        assertThat(keys).containsExactly("msg:wamid.BATCH0001", "msg:wamid.BATCH0002");

        String statusAndTenant = jdbc.sql("""
                        SELECT status || ':' || coalesce(tenant_id::text, '-') || ':' || phone_number_id
                        FROM inbound_event WHERE event_key = 'msg:wamid.BATCH0002'
                        """)
                .query(String.class).single();
        assertThat(statusAndTenant).isEqualTo("PENDING:-:200000000000001");

        String buttonId = jdbc.sql("""
                        SELECT payload #>> '{item,interactive,button_reply,id}'
                        FROM inbound_event WHERE event_key = 'msg:wamid.BATCH0002'
                        """)
                .query(String.class).single();
        assertThat(buttonId).isEqualTo("checkout.confirm");
    }

    @Test
    void rejectsInvalidSignatureWithoutStoringAnything() throws Exception {
        byte[] body = fixture("batch-messages.json");

        postSigned(body, sign(body, "attacker-secret")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/webhooks/whatsapp").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        assertThat(countEvents()).isZero();
    }

    @Test
    void rejectsSignedButMalformedPayload() throws Exception {
        byte[] body = "{\"object\":\"page\"}".getBytes(StandardCharsets.UTF_8);

        postSigned(body, sign(body, APP_SECRET)).andExpect(status().isBadRequest());

        assertThat(countEvents()).isZero();
    }

    private ResultActions postSigned(byte[] body, String signature) throws Exception {
        return mockMvc.perform(post("/webhooks/whatsapp")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Hub-Signature-256", signature)
                .content(body));
    }

    private long countEvents() {
        return jdbc.sql("SELECT count(*) FROM inbound_event").query(Long.class).single();
    }

    private static String sign(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = WhatsAppWebhookIT.class.getResourceAsStream("/whatsapp/" + name)) {
            return in.readAllBytes();
        }
    }
}
