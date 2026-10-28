package br.com.conversa.segment.restaurant;

import br.com.conversa.core.business.BusinessDirectory;
import br.com.conversa.core.segment.SegmentPack;
import br.com.conversa.core.tenant.TenantSettings;
import br.com.conversa.core.tool.Tool;
import br.com.conversa.segment.restaurant.tool.BusinessHoursTool;
import br.com.conversa.segment.restaurant.tool.BusinessInfoTool;
import br.com.conversa.segment.restaurant.tool.DeliveryFeeTool;
import br.com.conversa.segment.restaurant.tool.SearchMenuTool;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * Restaurant Segment Pack. Read tools (menu, opening hours, delivery fee, business info) since M4;
 * cart and checkout arrive in M7, see docs/ROADMAP.md.
 */
public final class RestaurantSegmentPack implements SegmentPack {

    public static final String ID = "restaurant";

    private final List<Tool> tools;

    public RestaurantSegmentPack(BusinessDirectory directory, TenantSettings settings, Clock clock) {
        this.tools = List.of(
                new SearchMenuTool(directory),
                new BusinessHoursTool(directory, clock),
                new DeliveryFeeTool(directory, settings),
                new BusinessInfoTool(settings));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public List<Tool> tools() {
        return tools;
    }

    @Override
    public String systemPrompt(String effectiveConfigJson) {
        JsonNode config = MAPPER.readTree(effectiveConfigJson);
        return resource("prompts/system.md")
                .replace("{{assistantName}}", config.path("assistant").path("name").asString("Assistente"))
                .replace("{{businessName}}", config.path("business").path("name").asString(""))
                .replace("{{tone}}", TONES.getOrDefault(config.path("assistant").path("tone").asString(), TONES.get("friendly")));
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final Map<String, String> TONES = Map.of(
            "friendly", "simpático e acolhedor",
            "formal", "cordial e formal, tratando o cliente por \"você\" sem gírias",
            "casual", "descontraído e próximo");
}
