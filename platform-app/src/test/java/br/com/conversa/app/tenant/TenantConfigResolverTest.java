package br.com.conversa.app.tenant;

import br.com.conversa.segment.restaurant.RestaurantSegmentPack;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantConfigResolverTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final TenantConfigResolver resolver = new TenantConfigResolver(mapper);
    private final RestaurantSegmentPack restaurant = new RestaurantSegmentPack(null, null, null, Clock.systemUTC());

    @Test
    void packDefaultsFillWhatTheTenantDoesNotSet() {
        JsonNode config = resolver.effectiveConfig(restaurant, """
                {"business": {"name": "Cantina da Praça"}, "assistant": {"name": "Rita"}}
                """);

        assertThat(config.path("business").path("name").asString()).isEqualTo("Cantina da Praça");
        assertThat(config.path("assistant").path("name").asString()).isEqualTo("Rita");
        assertThat(config.path("assistant").path("tone").asString()).isEqualTo("friendly");
        assertThat(config.path("handoff").path("pauseMinutes").asInt()).isEqualTo(60);
    }

    @Test
    void tenantArraysReplaceTheDefaults() {
        JsonNode config = resolver.effectiveConfig(restaurant, """
                {"business": {"name": "Cantina"}, "paymentMethods": ["pix_on_delivery"]}
                """);

        assertThat(config.path("paymentMethods")).hasSize(1);
        assertThat(config.path("paymentMethods").path(0).asString()).isEqualTo("pix_on_delivery");
    }

    @Test
    void rejectsConfigurationOutsideTheSchema() {
        assertThatThrownBy(() -> resolver.effectiveConfig(restaurant, """
                {"business": {"name": "Cantina"},
                 "assistant": {"tone": "sarcastic"},
                 "paymentMethods": ["bitcoin"],
                 "staffNumbers": ["12345"],
                 "fulfillment": {"delivery": false, "pickup": false},
                 "loyaltyProgram": true}
                """))
                .isInstanceOfSatisfying(InvalidTenantConfigException.class, e -> assertThat(e.errors())
                        .anySatisfy(error -> assertThat(error).contains("/assistant/tone"))
                        .anySatisfy(error -> assertThat(error).contains("/paymentMethods/0"))
                        .anySatisfy(error -> assertThat(error).contains("/staffNumbers/0"))
                        .anySatisfy(error -> assertThat(error).contains("/fulfillment"))
                        .anySatisfy(error -> assertThat(error).contains("loyaltyProgram")));
    }

    @Test
    void requiresTheBusinessName() {
        assertThatThrownBy(() -> resolver.effectiveConfig(restaurant, "{}"))
                .isInstanceOfSatisfying(InvalidTenantConfigException.class,
                        e -> assertThat(e.errors()).anySatisfy(error -> assertThat(error).contains("business")));
    }

    @Test
    void rejectsSomethingThatIsNotAJsonObject() {
        assertThatThrownBy(() -> resolver.effectiveConfig(restaurant, "[1, 2]"))
                .isInstanceOf(InvalidTenantConfigException.class);
        assertThatThrownBy(() -> resolver.effectiveConfig(restaurant, "{not json"))
                .isInstanceOf(InvalidTenantConfigException.class);
    }
}
