package br.com.conversa.segment.restaurant.tool;

import br.com.conversa.core.order.OrderDraft;
import br.com.conversa.core.order.OrderLine;

/**
 * The order summary the customer confirms (D-006), in pt-BR, built only from the draft.
 */
public final class OrderSummary {

    private OrderSummary() {
    }

    public static String text(OrderDraft draft) {
        StringBuilder text = new StringBuilder("*Resumo do pedido*\n");
        for (OrderLine line : draft.lines()) {
            text.append('\n').append(line.quantity()).append("x ").append(line.name());
            if (!line.options().isEmpty()) {
                text.append(" (").append(String.join(", ", line.options())).append(')');
            }
            text.append(" — ").append(ToolJson.formatMoney(line.lineTotal()));
            if (line.notes() != null && !line.notes().isBlank()) {
                text.append("\n   Obs.: ").append(line.notes());
            }
        }
        text.append("\n\nSubtotal: ").append(ToolJson.formatMoney(draft.subtotal()));
        if ("DELIVERY".equals(draft.fulfillment())) {
            text.append("\nTaxa de entrega (").append(draft.neighborhood()).append("): ")
                    .append(ToolJson.formatMoney(draft.deliveryFee()));
        }
        text.append("\n*Total: ").append(ToolJson.formatMoney(draft.total())).append('*');
        text.append("\n\n").append("DELIVERY".equals(draft.fulfillment())
                ? "Entrega: " + draft.address() + " - " + draft.neighborhood()
                : "Retirada no restaurante");
        text.append("\nPagamento: ").append(CartTools.PAYMENT_LABELS.getOrDefault(draft.paymentMethod(), draft.paymentMethod()));
        if (draft.changeFor() != null) {
            text.append(" (troco para ").append(ToolJson.formatMoney(draft.changeFor())).append(')');
        }
        text.append("\n\nEstá tudo certo? Toque em *Confirmar pedido* para enviar.");
        return text.toString();
    }
}
