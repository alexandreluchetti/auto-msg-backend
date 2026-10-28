package br.com.conversa.core.order;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One line of an order, priced from the catalog when the summary was built.
 *
 * @param unitPrice item price plus its options
 * @param options   chosen options, as shown to the customer ("Grande", "Borda Catupiry")
 */
public record OrderLine(UUID catalogItemId, String code, String name, int quantity, BigDecimal unitPrice,
                        List<String> options, String notes, BigDecimal lineTotal) {

    public OrderLine {
        options = List.copyOf(options);
    }
}
