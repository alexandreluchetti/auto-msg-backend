package br.com.conversa.segment.restaurant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RestaurantSegmentPackTest {

    private final RestaurantSegmentPack pack = new RestaurantSegmentPack();

    @Test
    void exposesStableIdentity() {
        assertThat(pack.id()).isEqualTo("restaurant");
        assertThat(pack.version()).isEqualTo(1);
        assertThat(pack.resourceBasePath()).isEqualTo("segments/restaurant/");
    }

    @Test
    void shipsConfigurationSchemaAndDefaults() {
        assertThat(pack.configSchemaJson()).contains("\"$schema\"", "paymentMethods");
        assertThat(pack.defaultConfigJson()).contains("\"handoff\"");
    }
}
