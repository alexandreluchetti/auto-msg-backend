package br.com.conversa.segment.restaurant.cart;

import br.com.conversa.core.conversation.ConversationState;
import br.com.conversa.core.tool.ToolInvocation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads and writes the cart under {@code "cart"} of the conversation state.
 */
public final class CartStore {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final ConversationState state;

    public CartStore(ConversationState state) {
        this.state = state;
    }

    public Cart load(ToolInvocation invocation) {
        JsonNode cart = MAPPER.readTree(state.read(invocation.tenantId(), invocation.conversationId())).path("cart");
        return cart.isObject() ? MAPPER.treeToValue(cart, Cart.class) : Cart.empty();
    }

    public void save(ToolInvocation invocation, Cart cart) {
        JsonNode current = MAPPER.readTree(state.read(invocation.tenantId(), invocation.conversationId()));
        ObjectNode next = current.isObject() ? (ObjectNode) current : MAPPER.createObjectNode();
        next.set("cart", MAPPER.valueToTree(cart));
        state.write(invocation.tenantId(), invocation.conversationId(), next.toString());
    }
}
