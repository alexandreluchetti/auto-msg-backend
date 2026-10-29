package br.com.conversa.app.memory;

import br.com.conversa.app.TestcontainersConfiguration;
import br.com.conversa.app.conversation.ReplyWorker;
import br.com.conversa.app.conversation.ScriptedLlm;
import br.com.conversa.app.inbound.InboundWorker;
import br.com.conversa.app.order.OrderService;
import br.com.conversa.app.outbound.FakeGraphApi;
import br.com.conversa.app.privacy.PrivacyService;
import br.com.conversa.app.tenant.TenantChannelService;
import br.com.conversa.app.tenant.TenantService;
import br.com.conversa.app.tenant.TenantTransactions;
import br.com.conversa.core.llm.LlmClient;
import br.com.conversa.core.llm.LlmContent;
import br.com.conversa.core.llm.LlmRequest;
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
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * H5: the assistant remembers the customer between conversations (name, latest orders, last
 * address) and keeps long conversations in context through a rolling summary.
 */
@SpringBootTest(properties = {
        "conversa.whatsapp.app-secret=" + MemoryIT.APP_SECRET,
        "conversa.workers.enabled=false",
        "conversa.assistant.debounce=0s",
        "conversa.assistant.pace-scale=0"
})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, MemoryIT.Llm.class})
@Testcontainers(disabledWithoutDocker = true)
class MemoryIT {

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
    TenantService tenantService;

    @Autowired
    TenantChannelService channels;

    @Autowired
    TenantTransactions tenantTransactions;

    @Autowired
    OrderService orders;

    @Autowired
    PrivacyService privacy;

    @Autowired
    ObjectMapper mapper;

    private TenantId tenant;

    @BeforeEach
    void cantina() {
        jdbc.sql("DELETE FROM outbound_message").update();
        jdbc.sql("DELETE FROM inbound_event").update();
        jdbc.sql("DELETE FROM customer_order").update();
        jdbc.sql("DELETE FROM tenant_channel").update();
        jdbc.sql("DELETE FROM tenant").update();
        llm.reset();
        graph.reset();
        tenant = tenantService.create("cantina", "Cantina", "restaurant", "{\"business\": {\"name\": \"Cantina\"}}");
        channels.register(tenant, PHONE_NUMBER_ID, "100000000000001", null);
    }

    @Test
    void aReturningCustomerIsRemembered() throws Exception {
        llm.then(ScriptedLlm.text("Oi!"));
        says("wamid.IN1", "Oi");
        UUID conversation = jdbc.sql("SELECT id FROM conversation").query(UUID.class).single();
        OrderDraft draft = new OrderDraft("DELIVERY", "Rua A, 1", "Centro", new BigDecimal("5.00"), "cash", null,
                List.of(new OrderLine(null, "P01", "Pizza Calabresa", 1, new BigDecimal("49.90"), List.of(), null,
                        new BigDecimal("49.90"))), new BigDecimal("49.90"), new BigDecimal("54.90"));
        tenantTransactions.runInTenant(tenant, () -> orders.confirm(tenant, conversation,
                orders.openCheckout(tenant, conversation, draft).toString(), "wamid.TAP", PHONE_NUMBER_ID, "Ana",
                mapper.createObjectNode()));
        llm.then(ScriptedLlm.text("Oi, Ana! Mando no mesmo endereço?"));

        says("wamid.IN2", "Quero o de sempre");

        String context = ((LlmContent.Text) llm.requests().getLast().messages().getLast().content().getFirst()).text();
        assertThat(context).contains("Nome do cliente no WhatsApp: Ana.",
                "Pedidos anteriores (mais recente primeiro):", "1x Pizza Calabresa (entrega, R$ 54,90)",
                "Endereço da última entrega: Rua A, 1 - Centro.");
        assertThat(context).doesNotContain(CUSTOMER);
    }

    @Test
    void aLongConversationIsSummarizedOnceInAWhile() throws Exception {
        llm.then(ScriptedLlm.text("Oi!"));
        says("wamid.IN0", "Oi");
        UUID conversation = jdbc.sql("SELECT id FROM conversation").query(UUID.class).single();
        for (int i = 1; i <= 40; i++) {
            jdbc.sql("""
                            INSERT INTO message (tenant_id, conversation_id, ref, direction, author, type, text, sent_at,
                                                 created_at)
                            VALUES (:tenant, :conversation, :ref, :direction, :author, 'text', :text, now(),
                                    now() - make_interval(mins => :age))
                            """)
                    .param("tenant", tenant.value()).param("conversation", conversation).param("ref", "old:" + i)
                    .param("direction", i % 2 == 0 ? "OUT" : "IN").param("author", i % 2 == 0 ? "BOT" : "CUSTOMER")
                    .param("text", "mensagem antiga " + i).param("age", 100 - i).update();
        }
        llm.then(ScriptedLlm.text("Cliente quer pizza grande para sábado."))
                .then(ScriptedLlm.text("Certo!"));

        says("wamid.IN1", "Então fechado?");

        LlmRequest summary = llm.requests().get(1);
        assertThat(summary.model()).isEqualTo("claude-haiku-5-5");
        assertThat(summary.system()).isEqualTo(ConversationSummarizer.PROMPT);
        assertThat(((LlmContent.Text) summary.messages().getFirst().content().getFirst()).text())
                .contains("Cliente: mensagem antiga 1", "Restaurante: mensagem antiga 2");
        LlmRequest answer = llm.requests().get(2);
        assertThat(answer.messages().getFirst().content().getFirst()).isEqualTo(
                new LlmContent.Text("[Resumo do começo desta conversa: Cliente quer pizza grande para sábado.]"));
        assertThat(jdbc.sql("SELECT summary FROM conversation_summary").query(String.class).single())
                .isEqualTo("Cliente quer pizza grande para sábado.");
        assertThat(jdbc.sql("SELECT count(*) FROM llm_call WHERE requested_model = 'claude-haiku-5-5'").query(Long.class).single())
                .isEqualTo(1);

        llm.then(ScriptedLlm.text("Combinado!"));
        says("wamid.IN2", "Ok");
        assertThat(llm.requests()).hasSize(4);

        tenantTransactions.runInTenant(tenant, () -> privacy.erase(conversation));
        assertThat(jdbc.sql("SELECT count(*) FROM conversation_summary").query(Long.class).single()).isZero();
    }

    private void says(String wamid, String text) throws Exception {
        byte[] body = """
                {"object": "whatsapp_business_account",
                 "entry": [{"id": "100000000000001", "changes": [{"field": "messages", "value": {
                   "messaging_product": "whatsapp",
                   "metadata": {"display_phone_number": "551630000000", "phone_number_id": "%s"},
                   "contacts": [{"profile": {"name": "Ana Souza"}, "wa_id": "%s"}],
                   "messages": [{"from": "%s", "id": "%s", "timestamp": "%d", "type": "text", "text": {"body": "%s"}}]
                 }}]}]}
                """.formatted(PHONE_NUMBER_ID, CUSTOMER, CUSTOMER, wamid, Instant.now().getEpochSecond(), text)
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
