package br.com.conversa.app.api;

import br.com.conversa.app.TestcontainersConfiguration;
import br.com.conversa.app.order.OrderService;
import br.com.conversa.app.tenant.TenantService;
import br.com.conversa.app.tenant.TenantTransactions;
import br.com.conversa.core.order.OrderDraft;
import br.com.conversa.core.order.OrderLine;
import br.com.conversa.core.tenant.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M8 acceptance: a confirmed order reaches an open panel over SSE in under 3 s, through a real
 * HTTP session (CSRF cookie, sign-in, session cookie).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "conversa.workers.enabled=false")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class PanelEventsIT {

    private static final String PASSWORD = "senha-forte-123";

    @LocalServerPort
    int port;

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

    @Test
    void aConfirmedOrderReachesThePanelInUnderThreeSeconds() throws Exception {
        jdbc.sql("DELETE FROM outbound_message").update();
        jdbc.sql("DELETE FROM customer_order").update();
        jdbc.sql("DELETE FROM tenant").update();
        TenantId tenant = tenantService.create("cantina", "Cantina", "restaurant", "{\"business\": {\"name\": \"Cantina\"}}");
        staff.upsert(tenant, "ana@cantina.test", "Ana", PASSWORD);

        CookieManager cookies = new CookieManager();
        HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
        http.send(request("/api/v1/auth/csrf").GET().build(), HttpResponse.BodyHandlers.discarding());
        String xsrf = cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst().orElseThrow();
        HttpResponse<String> login = http.send(request("/api/v1/auth/login")
                        .header("Content-Type", "application/json").header("X-XSRF-TOKEN", xsrf)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"email\": \"ana@cantina.test\", \"password\": \"" + PASSWORD + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(200);

        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        HttpResponse<Stream<String>> stream = http.send(request("/api/v1/events").header("Accept", "text/event-stream")
                .GET().build(), HttpResponse.BodyHandlers.ofLines());
        assertThat(stream.statusCode()).isEqualTo(200);
        Thread.ofVirtual().start(() -> stream.body().forEach(lines::add));
        assertThat(lines.poll(5, TimeUnit.SECONDS)).isEqualTo("event:ready");

        long start = System.nanoTime();
        UUID order = confirmOrder(tenant);
        String event = null;
        while (event == null || !event.startsWith("event:order.created")) {
            event = lines.poll(3, TimeUnit.SECONDS);
            assertThat(event).as("order event within 3 s").isNotNull();
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(3));
        assertThat(lines.poll(1, TimeUnit.SECONDS)).contains(order.toString());
        stream.body().close();
    }

    private UUID confirmOrder(TenantId tenant) {
        OrderDraft draft = new OrderDraft("PICKUP", null, null, BigDecimal.ZERO, "pix_on_delivery", null,
                List.of(new OrderLine(null, "B01", "Refrigerante Lata", 1, new BigDecimal("6.50"), List.of(), null,
                        new BigDecimal("6.50"))),
                new BigDecimal("6.50"), new BigDecimal("6.50"));
        return tenants.inTenant(tenant, () -> {
            UUID contact = jdbc.sql("INSERT INTO contact (tenant_id, wa_id) VALUES (:t, '5516990000001') RETURNING id")
                    .param("t", tenant.value()).query(UUID.class).single();
            UUID conversation = jdbc.sql("""
                            INSERT INTO conversation (tenant_id, contact_id, phone_number_id)
                            VALUES (:t, :c, '200000000000001') RETURNING id
                            """)
                    .param("t", tenant.value()).param("c", contact).query(UUID.class).single();
            UUID checkout = orders.openCheckout(tenant, conversation, draft);
            orders.confirm(tenant, conversation, checkout.toString(), "wamid.TAP", "200000000000001", "Cliente",
                    mapper.createObjectNode());
            return jdbc.sql("SELECT id FROM customer_order").query(UUID.class).single();
        });
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10));
    }
}
