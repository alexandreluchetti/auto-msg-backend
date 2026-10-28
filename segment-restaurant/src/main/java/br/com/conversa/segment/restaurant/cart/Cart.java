package br.com.conversa.segment.restaurant.cart;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * What the customer is putting together, kept in the conversation state. It holds choices only
 * (codes, quantities, option names); prices are always looked up in the catalog again.
 *
 * @param fulfillment    {@code delivery}, {@code pickup} or {@code null}
 * @param changeFor      cash the customer pays with, when they need change
 * @param noChangeNeeded the customer pays cash with the exact amount
 */
public record Cart(List<Line> lines, String fulfillment, String address, String neighborhood,
                   String paymentMethod, BigDecimal changeFor, boolean noChangeNeeded) {

    /** @param options option names exactly as registered in the catalog */
    public record Line(String code, int quantity, List<String> options, String notes) {

        public Line {
            options = List.copyOf(options);
        }
    }

    public Cart {
        lines = List.copyOf(lines);
    }

    public static Cart empty() {
        return new Cart(List.of(), null, null, null, null, null, false);
    }

    public Cart withLines(List<Line> newLines) {
        return new Cart(newLines, fulfillment, address, neighborhood, paymentMethod, changeFor, noChangeNeeded);
    }

    public Cart withLine(Line line) {
        List<Line> newLines = new ArrayList<>(lines);
        newLines.add(line);
        return withLines(newLines);
    }

    public Cart withFulfillment(String type, String newAddress, String newNeighborhood) {
        return new Cart(lines, type, newAddress, newNeighborhood, paymentMethod, changeFor, noChangeNeeded);
    }

    public Cart withPayment(String method, BigDecimal newChangeFor, boolean newNoChangeNeeded) {
        return new Cart(lines, fulfillment, address, neighborhood, method, newChangeFor, newNoChangeNeeded);
    }
}
