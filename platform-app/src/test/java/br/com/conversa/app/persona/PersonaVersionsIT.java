package br.com.conversa.app.persona;

import br.com.conversa.app.TestcontainersConfiguration;
import br.com.conversa.app.tenant.InvalidTenantConfigException;
import br.com.conversa.app.tenant.TenantService;
import br.com.conversa.app.tenant.TenantTransactions;
import br.com.conversa.core.segment.SegmentPack;
import br.com.conversa.core.tenant.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H2: configuration and prompt templates have a draft and a published version; WhatsApp uses only
 * the published ones, the simulator the draft.
 */
@SpringBootTest(properties = "conversa.workers.enabled=false")
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class PersonaVersionsIT {

    @Autowired
    TenantService tenants;

    @Autowired
    PromptTemplates prompts;

    @Autowired
    TenantTransactions tenantTransactions;

    @Autowired
    List<SegmentPack> packs;

    @Autowired
    JdbcClient jdbc;

    private TenantId cantina;
    private SegmentPack restaurant;

    @BeforeEach
    void cantina() {
        jdbc.sql("DELETE FROM outbound_message").update();
        jdbc.sql("DELETE FROM inbound_event").update();
        jdbc.sql("DELETE FROM customer_order").update();
        jdbc.sql("DELETE FROM tenant_channel").update();
        jdbc.sql("DELETE FROM tenant").update();
        cantina = tenants.create("cantina", "Cantina", "restaurant", "{\"business\": {\"name\": \"Cantina\"}}");
        restaurant = packs.stream().filter(pack -> pack.id().equals("restaurant")).findFirst().orElseThrow();
    }

    @Test
    void aDraftChangesNothingUntilItIsPublished() {
        assertThat(tenants.versions(cantina)).extracting(TenantService.ConfigVersion::status).containsExactly("PUBLISHED");

        int draft = tenants.saveDraft(cantina, """
                {"business": {"name": "Cantina"}, "persona": {"name": "Rita", "tone": "casual", "formality": 1,
                 "emojis": "moderate"}}""", null);

        assertThat(draft).isEqualTo(2);
        assertThat(tenants.effectiveConfig(cantina).path("persona").path("name").asString()).isEqualTo("Assistente");
        assertThat(tenants.effectiveDraftConfig(cantina).path("persona").path("name").asString()).isEqualTo("Rita");

        assertThat(tenants.publishDraft(cantina, null)).contains(2);

        assertThat(tenants.effectiveConfig(cantina).path("persona").path("tone").asString()).isEqualTo("casual");
        assertThat(tenants.draftConfig(cantina)).isEmpty();
        assertThat(tenants.versions(cantina)).extracting(version -> version.version() + " " + version.status())
                .containsExactly("2 PUBLISHED", "1 ARCHIVED");
        assertThat(tenants.publishDraft(cantina, null)).isEmpty();
    }

    @Test
    void savingADraftReplacesTheEarlierDraftAndValidatesIt() {
        tenants.saveDraft(cantina, "{\"business\": {\"name\": \"Cantina\"}, \"persona\": {\"name\": \"Rita\"}}", null);
        tenants.saveDraft(cantina, "{\"business\": {\"name\": \"Cantina\"}, \"persona\": {\"name\": \"Bia\"}}", null);

        assertThat(tenants.versions(cantina)).hasSize(2);
        assertThat(tenants.effectiveDraftConfig(cantina).path("persona").path("name").asString()).isEqualTo("Bia");
        assertThatThrownBy(() -> tenants.saveDraft(cantina,
                "{\"business\": {\"name\": \"Cantina\"}, \"persona\": {\"emojis\": \"muitos\"}}", null))
                .isInstanceOf(InvalidTenantConfigException.class);
        assertThat(tenants.effectiveDraftConfig(cantina).path("persona").path("name").asString()).isEqualTo("Bia");
    }

    @Test
    void thePacksTemplateIsTheSegmentDefaultAndATenantOverrideNeedsPublishing() {
        prompts.seed(restaurant);
        prompts.seed(restaurant);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM prompt_template
                        WHERE segment_id = 'restaurant' AND tenant_id IS NULL AND status = 'PUBLISHED'
                        """).query(Long.class).single()).isEqualTo(1);
        String shipped = restaurant.defaultPromptTemplate();
        assertThat(prompts.template(cantina, restaurant, PromptTemplates.Status.PUBLISHED)).isEqualTo(shipped);

        prompts.saveTenantDraft(cantina, "restaurant", "Você atende o {{business.name}}.\n\n{{persona}}");

        assertThat(prompts.template(cantina, restaurant, PromptTemplates.Status.PUBLISHED)).isEqualTo(shipped);
        assertThat(prompts.template(cantina, restaurant, PromptTemplates.Status.DRAFT)).startsWith("Você atende o");
        assertThat(prompts.publishTenantDraft(cantina, "restaurant")).isTrue();
        assertThat(prompts.template(cantina, restaurant, PromptTemplates.Status.PUBLISHED)).startsWith("Você atende o");

        TenantId other = tenants.create("other", "Other", "restaurant", "{\"business\": {\"name\": \"Other\"}}");
        assertThat(prompts.template(other, restaurant, PromptTemplates.Status.PUBLISHED)).isEqualTo(shipped);
        Long foreign = tenantTransactions.inTenant(other, () -> jdbc.sql(
                "SELECT count(*) FROM prompt_template WHERE tenant_id IS NOT NULL").query(Long.class).single());
        assertThat(foreign).isZero();
    }
}
