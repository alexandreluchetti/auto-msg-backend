package br.com.conversa.segment.restaurant;

import br.com.conversa.core.business.BusinessDirectory;
import br.com.conversa.core.conversation.ConversationState;
import br.com.conversa.core.segment.SegmentPack;
import br.com.conversa.core.tenant.TenantSettings;
import br.com.conversa.core.tool.Tool;
import br.com.conversa.segment.restaurant.cart.CartStore;
import br.com.conversa.segment.restaurant.tool.BusinessHoursTool;
import br.com.conversa.segment.restaurant.tool.BusinessInfoTool;
import br.com.conversa.segment.restaurant.tool.CartTools;
import br.com.conversa.segment.restaurant.tool.DeliveryFeeTool;
import br.com.conversa.segment.restaurant.tool.SearchMenuTool;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Restaurant Segment Pack: read tools (menu, opening hours, delivery fee, business info) and the
 * cart tools that end in the order summary with a confirmation button (M7).
 */
public final class RestaurantSegmentPack implements SegmentPack {

    public static final String ID = "restaurant";

    private final List<Tool> tools;

    public RestaurantSegmentPack(BusinessDirectory directory, TenantSettings settings, ConversationState state,
                                 Clock clock) {
        List<Tool> all = new ArrayList<>(List.of(
                new SearchMenuTool(directory),
                new BusinessHoursTool(directory, clock),
                new DeliveryFeeTool(directory, settings),
                new BusinessInfoTool(settings)));
        all.addAll(new CartTools(directory, settings, new CartStore(state), clock).tools());
        this.tools = List.copyOf(all);
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
}
