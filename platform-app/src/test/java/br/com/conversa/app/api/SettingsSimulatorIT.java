package br.com.conversa.app.api;

import br.com.conversa.app.TestcontainersConfiguration;
import br.com.conversa.app.business.BusinessDataImporter;
import br.com.conversa.app.conversation.ScriptedLlm;
import br.com.conversa.app.tenant.TenantService;
import br.com.conversa.app.tenant.TenantTransactions;
import br.com.conversa.core.llm.LlmClient;
import br.com.conversa.core.llm.LlmContent;
import br.com.conversa.core.llm.LlmResponse;
import br.com.conversa.core.llm.LlmUsage;
import br.com.conversa.core.tenant.TenantId;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * H8: admins edit the settings as a draft, try it in the simulator (real tools, scripted model, no
 * WhatsApp) and publish it; staff only read; the conversations list leaves the simulator out.
 */
@SpringBootTest(properties = "conversa.workers.enabled=false")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, SettingsSimulatorIT.Llm.class})
@Testcontainers(disabledWithoutDocker = true)
class SettingsSimulatorIT {

    private static final String PASSWORD = "senha-forte-123";
    private static final ScriptedLlm llm = new ScriptedLlm();

    @TestConfiguration(proxyBeanMethods = false)
    static class Llm {
        @Bean
        @Primary
        LlmClient scriptedLlm() {
            return llm;
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantService tenantService;

    @Autowired
    TenantTransactions tenants;

    @Autowired
    StaffDirectory staff;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    BusinessDataImporter importer;

    private TenantId cantina;

    @BeforeEach
    void cantina() {
        jdbc.sql("DELETE FROM outbound_message").update();
        jdbc.sql("DELETE FROM customer_order").update();
        jdbc.sql("DELETE FROM tenant").update();
        llm.reset();
        cantina = tenantService.create("cantina", "Cantina", "restaurant", "{\"business\": {\"name\": \"Cantina\"}}");
        staff.upsert(cantina, "dono@cantina.test", "Dono", PASSWORD, StaffUser.ADMIN);
        staff.upsert(cantina, "ana@cantina.test", "Ana", PASSWORD);
    }

    @Test
    void adminsEditADraftAndPublishIt() throws Exception {
        MockHttpSession admin = login("dono@cantina.test");
        MockHttpSession ana = login("ana@cantina.test");
        assertThat(json(mockMvc.perform(get("/api/v1/auth/me").session(admin)).andReturn()).path("role").asString())
                .isEqualTo("ADMIN");

        String draft = """
                {"business": {"name": "Cantina"}, "persona": {"name": "Bia", "tone": "casual"},
                 "humanization": {"maxBubbles": 1}}""";
        mockMvc.perform(put("/api/v1/settings/draft").session(ana).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(draft)).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/settings/draft").session(admin).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"humanization\": {\"maxBubbles\": 9}}"))
                .andExpect(status().is(422));
        mockMvc.perform(put("/api/v1/settings/draft").session(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(draft)).andExpect(status().isOk());

        JsonNode settings = json(mockMvc.perform(get("/api/v1/settings").session(ana)).andExpect(status().isOk())
                .andReturn());
        assertThat(settings.path("draft").path("persona").path("name").asString()).isEqualTo("Bia");
        assertThat(settings.path("effective").path("persona").path("tone").asString()).isEqualTo("casual");
        assertThat(settings.path("published").has("persona")).isFalse();
        assertThat(settings.path("schema").path("type").asString()).isEqualTo("object");
        assertThat(tenantService.effectiveConfig(cantina).path("persona").path("name").asString()).isNotEqualTo("Bia");

        JsonNode published = json(mockMvc.perform(post("/api/v1/settings/publish").session(admin).with(csrf()))
                .andExpect(status().isOk()).andReturn());
        assertThat(published.path("version").asInt()).isEqualTo(2);
        assertThat(tenantService.effectiveConfig(cantina).path("persona").path("name").asString()).isEqualTo("Bia");
        mockMvc.perform(post("/api/v1/settings/publish").session(admin).with(csrf())).andExpect(status().isConflict());
    }

    @Test
    void theSimulatorAnswersWithTheDraftAndSendsNothing() throws Exception {
        MockHttpSession admin = login("dono@cantina.test");
        mockMvc.perform(put("/api/v1/settings/draft").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"business\": {\"name\": \"Cantina\"}, \"persona\": {\"name\": \"Bia\"}}"))
                .andExpect(status().isOk());
        llm.then(ScriptedLlm.text("Oi! Aqui é a Bia 😊\n---\nO que vai ser hoje?"));

        JsonNode reply = json(say(admin, "{\"text\": \"Oi\"}").andExpect(status().isOk()).andReturn());

        assertThat(llm.requests().getLast().system()).contains("Bia");
        assertThat(reply.path("outcome").asString()).isEqualTo("ANSWERED");
        assertThat(reply.path("bubbles").valueStream().map(bubble -> bubble.path("text").asString()).toList())
                .containsExactly("Oi! Aqui é a Bia 😊", "O que vai ser hoje?");
        long first = reply.path("bubbles").path(0).path("atMs").asLong();
        assertThat(first).isBetween(1200L, 6000L);
        assertThat(reply.path("bubbles").path(1).path("atMs").asLong()).isGreaterThan(first);
        assertThat(reply.path("typingAtMs").path(0).asLong()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM outbound_message").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM llm_call").query(Long.class).single()).isEqualTo(1);

        JsonNode history = json(mockMvc.perform(get("/api/v1/simulator/messages").session(admin)).andReturn());
        assertThat(history.valueStream().map(line -> line.path("author").asString() + ":" + line.path("text").asString())
                .toList()).containsExactly("CUSTOMER:Oi", "BOT:Oi! Aqui é a Bia 😊\n\nO que vai ser hoje?");
        assertThat(json(mockMvc.perform(get("/api/v1/conversations").session(admin)).andReturn())).isEmpty();

        mockMvc.perform(delete("/api/v1/simulator/messages").session(admin).with(csrf())).andExpect(status().isNoContent());
        assertThat(json(mockMvc.perform(get("/api/v1/simulator/messages").session(admin)).andReturn())).isEmpty();
        say(login("ana@cantina.test"), "{\"text\": \"Oi\"}").andExpect(status().isForbidden());
    }

    @Test
    void aSimulatedOrderIsConfirmedWithoutCreatingIt() throws Exception {
        MockHttpSession admin = login("dono@cantina.test");
        importer.importMenu(cantina, "code;category;name;price\nP01;Pizzas;Pizza Calabresa;49,90\n", null);
        tenantService.updateConfig(cantina, """
                {"business": {"name": "Cantina"}, "fulfillment": {"delivery": true, "pickup": true, "minimumOrder": 0},
                 "paymentMethods": ["cash"]}""");
        llm.then(new LlmResponse("claude-opus-5-5", List.of(
                        new LlmContent.ToolCall("c1", "add_to_cart", "{\"code\": \"P01\"}"),
                        new LlmContent.ToolCall("c2", "set_fulfillment", "{\"type\": \"pickup\"}"),
                        new LlmContent.ToolCall("c3", "set_payment", "{\"method\": \"cash\", \"noChangeNeeded\": true}"),
                        new LlmContent.ToolCall("c4", "start_checkout", "{}")),
                LlmResponse.StopReason.TOOL_USE, new LlmUsage(100, 10, 0, 0), null));

        JsonNode summary = json(say(admin, "{\"text\": \"Uma calabresa pra retirar, pago em dinheiro\"}")
                .andExpect(status().isOk()).andReturn());
        assertThat(summary.path("outcome").asString()).isEqualTo("CHECKOUT");
        assertThat(summary.path("bubbles").path(0).path("text").asString()).contains("Pizza Calabresa");
        assertThat(summary.path("bubbles").path(0).path("choices").path(0).path("id").asString()).isEqualTo("sim:confirm");

        JsonNode confirmed = json(say(admin, "{\"text\": \"pode mandar\"}").andExpect(status().isOk()).andReturn());

        assertThat(confirmed.path("outcome").asString()).isEqualTo("CONFIRMED");
        assertThat(jdbc.sql("SELECT count(*) FROM customer_order").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM outbound_message").query(Long.class).single()).isZero();
    }

    @Test
    void theConversationsListAndDetail() throws Exception {
        UUID conversation = tenants.inTenant(cantina, () -> {
            UUID contact = jdbc.sql("""
                            INSERT INTO contact (tenant_id, wa_id, display_name) VALUES (:t, '5516990000001', 'Ana Souza')
                            RETURNING id""")
                    .param("t", cantina.value()).query(UUID.class).single();
            UUID id = jdbc.sql("""
                            INSERT INTO conversation (tenant_id, contact_id, phone_number_id, context)
                            VALUES (:t, :c, '200000000000001', '{"cart": {"lines": []}}') RETURNING id""")
                    .param("t", cantina.value()).param("c", contact).query(UUID.class).single();
            for (String[] message : new String[][] {{"IN", "CUSTOMER", "Oi"}, {"OUT", "BOT", "Oi! Tudo bem?"},
                    {"OUT", "STAFF", "Aqui é a Ana"}}) {
                jdbc.sql("""
                                INSERT INTO message (tenant_id, conversation_id, ref, direction, author, type, text, sent_at,
                                                     created_at)
                                VALUES (:t, :c, :ref, :direction, :author, 'text', :text, now(),
                                        clock_timestamp())""")
                        .param("t", cantina.value()).param("c", id).param("ref", "r:" + message[2])
                        .param("direction", message[0]).param("author", message[1]).param("text", message[2]).update();
            }
            return id;
        });
        MockHttpSession ana = login("ana@cantina.test");

        JsonNode list = json(mockMvc.perform(get("/api/v1/conversations?q=souza").session(ana)).andExpect(status().isOk())
                .andReturn());
        assertThat(list).singleElement().satisfies(item -> {
            assertThat(item.path("customerName").asString()).isEqualTo("Ana Souza");
            assertThat(item.path("state").asString()).isEqualTo("BOT");
            assertThat(item.path("lastMessage").asString()).isEqualTo("Aqui é a Ana");
            assertThat(item.path("lastAuthor").asString()).isEqualTo("STAFF");
        });
        assertThat(json(mockMvc.perform(get("/api/v1/conversations?state=HUMAN").session(ana)).andReturn())).isEmpty();

        JsonNode detail = json(mockMvc.perform(get("/api/v1/conversations/" + conversation).session(ana))
                .andExpect(status().isOk()).andReturn());
        assertThat(detail.path("messages").valueStream().map(message -> message.path("author").asString()).toList())
                .containsExactly("CUSTOMER", "BOT", "STAFF");
        assertThat(detail.path("state").path("cart").isObject()).isTrue();

        TenantId burger = tenantService.create("burger", "Burger", "restaurant", "{\"business\": {\"name\": \"Burger\"}}");
        staff.upsert(burger, "bia@burger.test", "Bia", PASSWORD);
        mockMvc.perform(get("/api/v1/conversations/" + conversation).session(login("bia@burger.test")))
                .andExpect(status().isNotFound());
    }

    private ResultActions say(MockHttpSession session, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/simulator/messages").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private MockHttpSession login(String email) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/api/v1/auth/login").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email + "\", \"password\": \"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
        return session;
    }

    private JsonNode json(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }
}
