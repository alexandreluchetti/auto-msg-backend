package br.com.conversa.app.tenant;

import br.com.conversa.app.TestcontainersConfiguration;
import br.com.conversa.core.tenant.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M3 acceptance: a tenant's context never sees or writes another tenant's rows, and an invalid
 * configuration is rejected.
 */
@SpringBootTest(properties = {
        "conversa.whatsapp.app-secret=" + TenantIsolationIT.APP_SECRET,
        "conversa.workers.enabled=false"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class TenantIsolationIT {

    static final String APP_SECRET = "it-app-secret";
    private static final String PHONE_A = "200000000000001";
    private static final String PHONE_B = "200000000000002";
    private static final String VALID_CONFIG = "{\"business\": {\"name\": \"Cantina\"}}";

    @Autowired
    TenantService tenantService;

    @Autowired
    TenantChannelService channels;

    @Autowired
    TenantTransactions tenantTransactions;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MockMvc mockMvc;

    private TenantId tenantA;
    private TenantId tenantB;

    @BeforeEach
    void twoTenants() {
        jdbc.sql("DELETE FROM outbound_message").update();
        jdbc.sql("DELETE FROM inbound_event").update();
        jdbc.sql("DELETE FROM tenant_channel").update();
        jdbc.sql("DELETE FROM tenant").update();
        tenantA = tenantService.create("tenant-a", "Tenant A", "restaurant", VALID_CONFIG);
        tenantB = tenantService.create("tenant-b", "Tenant B", "restaurant", VALID_CONFIG);
        channels.register(tenantA, PHONE_A, "100000000000001", null);
        channels.register(tenantB, PHONE_B, "100000000000002", null);
    }

    @Test
    void tenantContextSeesOnlyItsOwnRows() {
        tenantTransactions.runInTenant(tenantA, () -> {
            assertThat(jdbc.sql("SELECT slug FROM tenant").query(String.class).list())
                    .containsExactly("tenant-a");
            assertThat(jdbc.sql("SELECT phone_number_id FROM tenant_channel").query(String.class).list())
                    .containsExactly(PHONE_A);
            assertThat(jdbc.sql("UPDATE tenant SET display_name = 'hacked' WHERE id = :id")
                    .param("id", tenantB.value()).update()).isZero();
        });

        assertThat(jdbc.sql("SELECT display_name FROM tenant WHERE id = :id")
                .param("id", tenantB.value()).query(String.class).single()).isEqualTo("Tenant B");
    }

    @Test
    void tenantContextCannotWriteRowsOfAnotherTenant() {
        assertThatThrownBy(() -> tenantTransactions.runInTenant(tenantA, () -> jdbc.sql("""
                        INSERT INTO tenant_channel (tenant_id, phone_number_id) VALUES (:tenantB, '200000000000099')
                        """)
                .param("tenantB", tenantB.value()).update()))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("violates row-level security policy");
    }

    @Test
    void applicationRoleWithoutTenantSeesNothing() {
        Long visible = transactions.execute(status -> {
            jdbc.sql("SET LOCAL ROLE " + TenantTransactions.APP_ROLE).update();
            return jdbc.sql("SELECT (SELECT count(*) FROM tenant) + (SELECT count(*) FROM tenant_channel)")
                    .query(Long.class).single();
        });

        assertThat(visible).isZero();
    }

    @Test
    void transactionBoundToOneTenantCannotSwitchToAnother() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            tenantTransactions.runInTenant(tenantA, () -> { });
            tenantTransactions.runInTenant(tenantB, () -> { });
        })).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void edgeResolvesTheTenantOfANumberWithoutTenantContext() throws Exception {
        assertThat(channels.tenantOf(PHONE_B)).contains(tenantB);
        assertThat(channels.tenantOf("299999999999999")).isEmpty();

        byte[] body = """
                {"object": "whatsapp_business_account", "entry": [{"id": "100000000000002", "changes": [{
                  "field": "messages", "value": {"messaging_product": "whatsapp",
                  "metadata": {"display_phone_number": "551630000002", "phone_number_id": "%s"},
                  "messages": [{"from": "5516990000001", "id": "wamid.ISO0001", "timestamp": "1761650000",
                                "type": "text", "text": {"body": "Oi"}}]}}]}]}
                """.formatted(PHONE_B).getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(post("/webhooks/whatsapp").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", sign(body)).content(body))
                .andExpect(status().isOk());

        assertThat(jdbc.sql("SELECT tenant_id FROM inbound_event").query(UUID.class).single())
                .isEqualTo(tenantB.value());
    }

    @Test
    void numberOfOneTenantCannotBeRegisteredToAnother() {
        assertThatThrownBy(() -> channels.register(tenantB, PHONE_A, null, null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(channels.tenantOf(PHONE_A)).contains(tenantA);
    }

    @Test
    void invalidConfigurationIsRejectedAndNothingChanges() {
        assertThatThrownBy(() -> tenantService.create("tenant-c", "Tenant C", "restaurant",
                "{\"business\": {\"name\": \"C\"}, \"paymentMethods\": []}"))
                .isInstanceOf(InvalidTenantConfigException.class);
        assertThat(tenantService.findBySlug("tenant-c")).isEmpty();

        assertThatThrownBy(() -> tenantService.updateConfig(tenantA, "{\"handoff\": {\"pauseMinutes\": 0}}"))
                .isInstanceOf(InvalidTenantConfigException.class);
        assertThat(tenantService.effectiveConfig(tenantA).path("business").path("name").asString())
                .isEqualTo("Cantina");
    }

    @Test
    void validConfigurationChangeIsStoredAndMergedWithDefaults() {
        tenantService.updateConfig(tenantA, """
                {"business": {"name": "Cantina Nova"}, "handoff": {"pauseMinutes": 30}}
                """);

        var config = tenantService.effectiveConfig(tenantA);
        assertThat(config.path("business").path("name").asString()).isEqualTo("Cantina Nova");
        assertThat(config.path("handoff").path("pauseMinutes").asInt()).isEqualTo(30);
        assertThat(config.path("assistant").path("tone").asString()).isEqualTo("friendly");
    }

    @Test
    void unknownSegmentIsRejected() {
        assertThatThrownBy(() -> tenantService.create("clinic", "Clinic", "clinic", "{}"))
                .isInstanceOf(SegmentPackRegistry.UnknownSegmentException.class);
    }

    private static String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(APP_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}
