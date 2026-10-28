package br.com.conversa.app.tenant;

import br.com.conversa.app.TestcontainersConfiguration;
import br.com.conversa.core.tenant.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "conversa.workers.enabled=false",
        "conversa.bootstrap.slug=pilot-restaurant",
        "conversa.bootstrap.display-name=Pilot Restaurant",
        "conversa.bootstrap.config-json={\"business\": {\"name\": \"Pilot\", \"city\": \"Bonfim Paulista\"}}",
        "conversa.bootstrap.phone-number-id=200000000000001",
        "conversa.bootstrap.waba-id=100000000000001",
        "conversa.bootstrap.access-token-ref=WHATSAPP_TOKEN_PILOT"
})
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class TenantBootstrapIT {

    @Autowired
    TenantService tenants;

    @Autowired
    TenantChannelService channels;

    @Test
    void seedsTheConfiguredTenantAndNumberAtStartup() {
        TenantId tenant = tenants.findBySlug("pilot-restaurant").orElseThrow();

        assertThat(channels.tenantOf("200000000000001")).contains(tenant);
        assertThat(channels.accessTokenRef("200000000000001")).contains("WHATSAPP_TOKEN_PILOT");
        var config = tenants.effectiveConfig(tenant);
        assertThat(config.path("business").path("city").asString()).isEqualTo("Bonfim Paulista");
        assertThat(config.path("handoff").path("pauseMinutes").asInt()).isEqualTo(60);
    }
}
