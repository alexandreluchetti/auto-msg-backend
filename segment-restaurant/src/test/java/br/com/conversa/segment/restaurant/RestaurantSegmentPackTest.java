package br.com.conversa.segment.restaurant;

import br.com.conversa.core.tool.Tool;
import br.com.conversa.segment.restaurant.tool.FakeBusinessDirectory;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class RestaurantSegmentPackTest {

    private final RestaurantSegmentPack pack =
            new RestaurantSegmentPack(new FakeBusinessDirectory(), tenant -> "{}", Clock.systemUTC());

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

    @Test
    void exposesTheReadTools() {
        assertThat(pack.tools()).extracting(Tool::name)
                .containsExactly("search_menu", "get_business_hours", "get_delivery_fee", "get_business_info");
        assertThat(pack.tools()).allSatisfy(tool -> {
            assertThat(tool.name()).matches("[a-z_]+");
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.inputSchema()).contains("\"additionalProperties\": false");
        });
    }
}
