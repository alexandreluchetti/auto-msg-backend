package br.com.conversa.app.api;

import br.com.conversa.app.TestcontainersConfiguration;
import br.com.conversa.app.order.OrderService;
import br.com.conversa.app.tenant.TenantService;
import br.com.conversa.app.tenant.TenantTransactions;
import br.com.conversa.core.order.OrderDraft;
import br.com.conversa.core.order.OrderLine;
import br.com.conversa.core.tenant.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M8 panel API: staff sign in, see only their business's orders, move them (the customer is told)
 * and hand conversations back to the bot.
 */
@SpringBootTest(properties = "conversa.workers.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class PanelApiIT {

    private static final String PHONE_NUMBER_ID = "200000000000001";
    private static final String CUSTOMER = "5516990000001";
    private static final String PASSWORD = "senha-forte-123";

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
    OrderService orders;

    @Autowired
    ObjectMapper mapper;

    private TenantId cantina;
    private TenantId burger;

    @BeforeEach
    void twoBusinesses() {
        jdbc.sql("DELETE FROM outbound_message").update();
        jdbc.sql("DELETE FROM inbound_event").update();
        jdbc.sql("DELETE FROM customer_order").update();
        jdbc.sql("DELETE FROM tenant").update();
        cantina = tenantService.create("cantina", "Cantina", "restaurant", "{\"business\": {\"name\": \"Cantina\"}}");
        burger = tenantService.create("burger", "Burger", "restaurant", "{\"business\": {\"name\": \"Burger\"}}");
        staff.upsert(cantina, "ana@cantina.test", "Ana", PASSWORD);
        staff.upsert(burger, "bia@burger.test", "Bia", PASSWORD);
    }

    @Test
    void signsInWithEmailAndPasswordOnly() throws Exception {
        mockMvc.perform(get("/api/v1/orders")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"ana@cantina.test\", \"password\": \"errada-123456\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"ana@cantina.test\", \"password\": \"" + PASSWORD + "\"}"))
                .andExpect(status().isForbidden());

        MockHttpSession session = login("ANA@cantina.test");
        JsonNode me = json(mockMvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(me.path("name").asString()).isEqualTo("Ana");
        assertThat(me.path("tenantId").asString()).isEqualTo(cantina.toString());
        mockMvc.perform(post("/api/v1/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void theBoardShowsOnlyTheBusinessOwnOrders() throws Exception {
        UUID own = order(cantina, "wamid.T1");
        order(burger, "wamid.T2");

        JsonNode board = json(mockMvc.perform(get("/api/v1/orders").session(login("ana@cantina.test")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(board).singleElement().satisfies(order -> {
            assertThat(order.path("id").asString()).isEqualTo(own.toString());
            assertThat(order.path("number").asInt()).isEqualTo(1);
            assertThat(order.path("status").asString()).isEqualTo("RECEIVED");
            assertThat(order.path("customerPhone").asString()).isEqualTo(CUSTOMER);
            assertThat(order.path("total").decimalValue()).isEqualByComparingTo("54.90");
            assertThat(order.path("items").path(0).path("name").asString()).isEqualTo("Pizza Calabresa");
            assertThat(order.path("items").path(0).path("options").path(0).asString()).isEqualTo("Tamanho: Grande");
        });
        mockMvc.perform(get("/api/v1/orders/" + own).session(login("bia@burger.test"))).andExpect(status().isNotFound());
    }

    @Test
    void movingAnOrderTellsTheCustomer() throws Exception {
        UUID order = order(cantina, "wamid.T1");
        openWindow();
        MockHttpSession session = login("ana@cantina.test");

        mockMvc.perform(post("/api/v1/orders/" + order + "/status").session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"PREPARING\"}"))
                .andExpect(status().isForbidden());
        move(session, order, "PREPARING").andExpect(status().isOk());
        move(session, order, "READY_FOR_PICKUP").andExpect(status().isConflict());
        move(session, order, "OUT_FOR_DELIVERY").andExpect(status().isOk());
        move(session, order, "PREPARING").andExpect(status().isConflict());
        move(session, order, "COMPLETED").andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT status FROM customer_order").query(String.class).single()).isEqualTo("COMPLETED");
        assertThat(jdbc.sql("SELECT payload::text FROM outbound_message WHERE kind = 'TEXT' ORDER BY id")
                .query(String.class).list().stream().map(payload -> mapper.readTree(payload).path("body").asString()))
                .containsExactly("Seu pedido *#1* já está sendo preparado!", "Seu pedido *#1* saiu para entrega!",
                        "Pedido *#1* concluído. Obrigado pela preferência!");
    }

    @Test
    void outsideTheWindowWithoutATemplateTheCustomerIsNotNotified() throws Exception {
        UUID order = order(cantina, "wamid.T1");

        move(login("ana@cantina.test"), order, "CANCELLED").andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT count(*) FROM outbound_message").query(Long.class).single()).isZero();
    }

    @Test
    void staffTakeAConversationOver() throws Exception {
        UUID conversation = conversation(cantina);
        MockHttpSession session = login("ana@cantina.test");

        mockMvc.perform(post("/api/v1/conversations/" + UUID.randomUUID() + "/takeover").session(session).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/conversations/" + conversation + "/takeover")
                        .session(login("bia@burger.test")).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/conversations/" + conversation + "/takeover").session(session).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(jdbc.sql("SELECT state || ':' || handoff_reason FROM conversation").query(String.class).single())
                .isEqualTo("HUMAN:STAFF_TAKEOVER");
    }

    @Test
    void handsAConversationBackToTheBot() throws Exception {
        UUID conversation = conversation(cantina);
        jdbc.sql("""
                        UPDATE conversation SET state = 'HUMAN', paused_until = now() + interval '1 hour',
                               handoff_reason = 'CUSTOMER_REQUEST', handoff_at = now()
                        """).update();
        MockHttpSession session = login("ana@cantina.test");

        JsonNode handoffs = json(mockMvc.perform(get("/api/v1/conversations/handoffs").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(handoffs).singleElement().satisfies(handoff -> {
            assertThat(handoff.path("id").asString()).isEqualTo(conversation.toString());
            assertThat(handoff.path("handoffReason").asString()).isEqualTo("CUSTOMER_REQUEST");
        });
        assertThat(json(mockMvc.perform(get("/api/v1/conversations/handoffs").session(login("bia@burger.test")))
                .andReturn().getResponse().getContentAsString())).isEmpty();

        mockMvc.perform(post("/api/v1/conversations/" + conversation + "/resume").session(session).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(jdbc.sql("SELECT state FROM conversation").query(String.class).single()).isEqualTo("BOT");
    }

    private org.springframework.test.web.servlet.ResultActions move(MockHttpSession session, UUID order, String target)
            throws Exception {
        return mockMvc.perform(post("/api/v1/orders/" + order + "/status").session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"" + target + "\"}"));
    }

    private MockHttpSession login(String email) throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/api/v1/auth/login").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email + "\", \"password\": \"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
        return session;
    }

    /** A confirmed order of one pizza delivered to the Centro. */
    private UUID order(TenantId tenant, String tapWamid) {
        UUID conversation = conversation(tenant);
        OrderDraft draft = new OrderDraft("DELIVERY", "Rua A, 1", "Centro", new BigDecimal("5.00"), "cash", null,
                List.of(new OrderLine(null, "P01", "Pizza Calabresa", 1, new BigDecimal("49.90"), List.of("Tamanho: Grande"),
                        null, new BigDecimal("49.90"))),
                new BigDecimal("49.90"), new BigDecimal("54.90"));
        return tenants.inTenant(tenant, () -> {
            UUID checkout = orders.openCheckout(tenant, conversation, draft);
            orders.confirm(tenant, conversation, checkout.toString(), tapWamid, PHONE_NUMBER_ID, "Cliente", mapper.createObjectNode());
            return jdbc.sql("SELECT id FROM customer_order WHERE checkout_id = :id").param("id", checkout)
                    .query(UUID.class).single();
        });
    }

    private UUID conversation(TenantId tenant) {
        return tenants.inTenant(tenant, () -> {
            UUID contact = jdbc.sql("INSERT INTO contact (tenant_id, wa_id) VALUES (:t, :wa) RETURNING id")
                    .param("t", tenant.value()).param("wa", CUSTOMER).query(UUID.class).single();
            return jdbc.sql("""
                            INSERT INTO conversation (tenant_id, contact_id, phone_number_id) VALUES (:t, :c, :p) RETURNING id
                            """)
                    .param("t", tenant.value()).param("c", contact).param("p", PHONE_NUMBER_ID).query(UUID.class).single();
        });
    }

    private void openWindow() {
        jdbc.sql("""
                        INSERT INTO inbound_event (event_key, event_type, field, phone_number_id, wa_id, payload, status)
                        VALUES ('msg:wamid.RECENT', 'MESSAGE', 'messages', :phone, :waId, CAST(:payload AS jsonb), 'PROCESSED')
                        """)
                .param("phone", PHONE_NUMBER_ID).param("waId", CUSTOMER)
                .param("payload", "{\"item\":{\"timestamp\":\"" + Instant.now().getEpochSecond() + "\"}}")
                .update();
    }

    private JsonNode json(String body) {
        return mapper.readTree(body);
    }
}
