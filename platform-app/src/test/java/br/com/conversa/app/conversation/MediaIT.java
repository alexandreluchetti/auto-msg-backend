package br.com.conversa.app.conversation;

import br.com.conversa.app.TestcontainersConfiguration;
import br.com.conversa.app.inbound.InboundWorker;
import br.com.conversa.app.outbound.FakeGraphApi;
import br.com.conversa.app.tenant.TenantChannelService;
import br.com.conversa.app.tenant.TenantService;
import br.com.conversa.core.llm.LlmClient;
import br.com.conversa.core.llm.LlmContent;
import br.com.conversa.core.speech.SpeechToText;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * H6: voice notes are transcribed (by a fake speech-to-text) and answered like text, with their cost
 * recorded; a failed transcription asks the customer to write; images go to the model with their
 * message, for that answer only.
 */
@SpringBootTest(properties = {
        "conversa.whatsapp.app-secret=" + MediaIT.APP_SECRET,
        "conversa.whatsapp.access-token=it-platform-token",
        "conversa.workers.enabled=false",
        "conversa.assistant.debounce=0s",
        "conversa.assistant.pace-scale=0"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MediaIT.Fakes.class})
@Testcontainers(disabledWithoutDocker = true)
class MediaIT {

    static final String APP_SECRET = "it-app-secret";
    private static final String PHONE_NUMBER_ID = "200000000000001";
    private static final String CUSTOMER = "5516990000001";
    private static final ScriptedLlm llm = new ScriptedLlm();
    private static final FakeGraphApi graph = new FakeGraphApi();
    private static final FakeSpeech speech = new FakeSpeech();

    static final class FakeSpeech implements SpeechToText {
        final List<byte[]> heard = new CopyOnWriteArrayList<>();
        volatile String text;

        @Override
        public boolean configured() {
            return true;
        }

        @Override
        public Optional<Transcription> transcribe(byte[] audio, String mimeType) {
            heard.add(audio);
            return Optional.ofNullable(text).map(said -> new Transcription(said, "gpt-4o-mini-transcribe", 30));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fakes {
        @Bean
        @Primary
        LlmClient scriptedLlm() {
            return llm;
        }

        @Bean
        @Primary
        SpeechToText fakeSpeech() {
            return speech;
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
    TenantService tenantService;

    @Autowired
    TenantChannelService channels;

    private TenantId tenant;

    @BeforeEach
    void cantina() {
        jdbc.sql("DELETE FROM outbound_message").update();
        jdbc.sql("DELETE FROM inbound_event").update();
        jdbc.sql("DELETE FROM tenant_channel").update();
        jdbc.sql("DELETE FROM tenant").update();
        llm.reset();
        graph.reset();
        speech.heard.clear();
        speech.text = null;
        tenant = tenantService.create("cantina", "Cantina", "restaurant", "{\"business\": {\"name\": \"Cantina\"}}");
        channels.register(tenant, PHONE_NUMBER_ID, "100000000000001", null);
    }

    @Test
    void aVoiceNoteIsTranscribedAndAnswered() throws Exception {
        graph.media("AUDIO1", "audio/ogg; codecs=opus", new byte[]{1, 2, 3});
        speech.text = "Oi, vocês têm pizza de calabresa?";
        llm.then(ScriptedLlm.text("Temos sim!"));

        sends("wamid.IN1", """
                "type": "audio", "audio": {"id": "AUDIO1", "mime_type": "audio/ogg; codecs=opus", "voice": true}""");

        assertThat(speech.heard).singleElement().isEqualTo(new byte[]{1, 2, 3});
        assertThat(lastCustomerText()).contains("[áudio] Oi, vocês têm pizza de calabresa?");
        assertThat(jdbc.sql("SELECT text FROM message WHERE wamid = 'wamid.IN1'").query(String.class).single())
                .isEqualTo("[áudio] Oi, vocês têm pizza de calabresa?");
        assertThat(jdbc.sql("SELECT media_status FROM message WHERE wamid = 'wamid.IN1'").query(String.class).single())
                .isEqualTo("DONE");
        assertThat(jdbc.sql("SELECT cost_usd FROM llm_call WHERE stop_reason = 'TRANSCRIPTION'")
                .query(BigDecimal.class).single()).isEqualByComparingTo("0.0015");

        llm.then(ScriptedLlm.text("Beleza!"));
        sends("wamid.IN2", "\"type\": \"text\", \"text\": {\"body\": \"Quero uma\"}");
        assertThat(speech.heard).hasSize(1);
    }

    @Test
    void aVoiceNoteThatCannotBeTranscribedAsksToWrite() throws Exception {
        graph.media("AUDIO1", "audio/ogg", new byte[]{1});
        llm.then(ScriptedLlm.text("Não consegui ouvir seu áudio aqui, me manda por escrito?"));

        sends("wamid.IN1", "\"type\": \"audio\", \"audio\": {\"id\": \"AUDIO1\", \"mime_type\": \"audio/ogg\"}");

        assertThat(lastCustomerText()).contains(MediaInbox.NOT_TRANSCRIBED);
        assertThat(jdbc.sql("SELECT media_status FROM message WHERE wamid = 'wamid.IN1'").query(String.class).single())
                .isEqualTo("FAILED");
        assertThat(jdbc.sql("SELECT count(*) FROM llm_call WHERE stop_reason = 'TRANSCRIPTION'").query(Long.class).single())
                .isZero();
    }

    @Test
    void aTooLongVoiceNoteIsNotTranscribed() throws Exception {
        graph.media("AUDIO1", "audio/ogg", new byte[300_000]);
        speech.text = "um áudio enorme";
        llm.then(ScriptedLlm.text("Me conta por escrito?"));

        sends("wamid.IN1", "\"type\": \"audio\", \"audio\": {\"id\": \"AUDIO1\", \"mime_type\": \"audio/ogg\"}");

        assertThat(speech.heard).isEmpty();
        assertThat(lastCustomerText()).contains(MediaInbox.NOT_TRANSCRIBED);
    }

    @Test
    void anImageGoesToTheModelWithItsMessageForThatAnswerOnly() throws Exception {
        graph.media("IMG1", "image/jpeg", new byte[]{9, 9});
        llm.then(ScriptedLlm.text("Que foto linda! Essa é a nossa margherita."));

        sends("wamid.IN1", """
                "type": "image", "image": {"id": "IMG1", "mime_type": "image/jpeg", "caption": "tem essa?"}""");

        List<LlmContent> content = llm.requests().getLast().messages().getLast().content();
        assertThat(content).contains(new LlmContent.Text("[imagem] tem essa?"));
        assertThat(content).filteredOn(LlmContent.Image.class::isInstance).singleElement()
                .satisfies(image -> {
                    assertThat(((LlmContent.Image) image).mediaType()).isEqualTo("image/jpeg");
                    assertThat(((LlmContent.Image) image).data()).containsExactly(9, 9);
                });

        jdbc.sql("UPDATE outbound_message SET status = 'SENT'").update(); // the answer left, so it is not cancelled
        llm.then(ScriptedLlm.text("Ótimo!"));
        sends("wamid.IN2", "\"type\": \"text\", \"text\": {\"body\": \"Quero uma\"}");
        assertThat(llm.requests().getLast().messages()).allSatisfy(message ->
                assertThat(message.content()).noneMatch(LlmContent.Image.class::isInstance));
    }

    private String lastCustomerText() {
        return llm.requests().getLast().messages().getLast().content().stream()
                .filter(LlmContent.Text.class::isInstance).map(text -> ((LlmContent.Text) text).text())
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private void sends(String wamid, String message) throws Exception {
        byte[] body = """
                {"object": "whatsapp_business_account",
                 "entry": [{"id": "100000000000001", "changes": [{"field": "messages", "value": {
                   "messaging_product": "whatsapp",
                   "metadata": {"display_phone_number": "551630000000", "phone_number_id": "%s"},
                   "contacts": [{"profile": {"name": "Ana Souza"}, "wa_id": "%s"}],
                   "messages": [{"from": "%s", "id": "%s", "timestamp": "%d", %s}]
                 }}]}]}
                """.formatted(PHONE_NUMBER_ID, CUSTOMER, CUSTOMER, wamid, Instant.now().getEpochSecond(), message)
                .getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(APP_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mockMvc.perform(MockMvcRequestBuilders.post("/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=" + HexFormat.of().formatHex(mac.doFinal(body)))
                        .content(body))
                .andExpect(status().isOk());
        inboundWorker.runOnce();
        replyWorker.runOnce();
    }
}
