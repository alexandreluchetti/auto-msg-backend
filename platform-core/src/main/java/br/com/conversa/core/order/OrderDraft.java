package br.com.conversa.core.order;

import java.math.BigDecimal;
import java.util.List;

/**
 * An order as summarized to the customer before confirmation (D-006). Built by code from the
 * business's data, never by the LLM; confirming creates exactly this order.
 *
 * @param fulfillment   {@code DELIVERY} or {@code PICKUP}
 * @param paymentMethod one of the tenant's payment methods, e.g. {@code cash}
 * @param changeFor     cash the customer will pay with, when they need change
 */
public record OrderDraft(
        String fulfillment,
        String address,
        String neighborhood,
        BigDecimal deliveryFee,
        String paymentMethod,
        BigDecimal changeFor,
        List<OrderLine> lines,
        BigDecimal subtotal,
        BigDecimal total) {

    public OrderDraft {
        lines = List.copyOf(lines);
    }
}
