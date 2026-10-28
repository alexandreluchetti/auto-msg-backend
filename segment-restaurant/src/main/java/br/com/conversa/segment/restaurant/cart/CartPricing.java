package br.com.conversa.segment.restaurant.cart;

import br.com.conversa.core.business.DeliveryZone;
import br.com.conversa.core.catalog.CatalogItem;
import br.com.conversa.core.catalog.CatalogOption;
import br.com.conversa.core.order.OrderLine;
import br.com.conversa.core.text.SearchText;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Prices a cart from the business's own data (D-007): item prices, option deltas and the delivery
 * fee of the neighborhood. The LLM never supplies an amount.
 */
public final class CartPricing {

    /** The cart with prices, and what keeps it from being ordered as it is. */
    public record Priced(List<OrderLine> lines, BigDecimal subtotal, DeliveryZone zone, BigDecimal deliveryFee,
                         BigDecimal total, List<String> problems) {
    }

    private CartPricing() {
    }

    public static Priced price(Cart cart, List<CatalogItem> catalog, List<DeliveryZone> zones) {
        List<OrderLine> lines = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (int index = 0; index < cart.lines().size(); index++) {
            Cart.Line line = cart.lines().get(index);
            Optional<CatalogItem> item = catalog.stream().filter(candidate -> candidate.code().equals(line.code())).findFirst();
            if (item.isEmpty()) {
                problems.add("line " + (index + 1) + ": item " + line.code() + " is no longer on the menu");
                continue;
            }
            if (!item.get().available()) {
                problems.add("line " + (index + 1) + ": " + item.get().name() + " is out of stock today");
            }
            BigDecimal unit = item.get().price();
            List<String> labels = new ArrayList<>();
            for (String name : line.options()) {
                Optional<CatalogOption> option = item.get().options().stream()
                        .filter(candidate -> candidate.name().equals(name)).findFirst();
                if (option.isEmpty()) {
                    problems.add("line " + (index + 1) + ": option " + name + " is no longer available");
                    continue;
                }
                unit = unit.add(option.get().priceDelta());
                labels.add(option.get().group() + ": " + option.get().name());
            }
            BigDecimal lineTotal = unit.multiply(BigDecimal.valueOf(line.quantity()));
            subtotal = subtotal.add(lineTotal);
            lines.add(new OrderLine(item.get().id(), item.get().code(), item.get().name(), line.quantity(), unit, labels,
                    line.notes(), lineTotal));
        }
        DeliveryZone zone = "delivery".equals(cart.fulfillment()) ? zoneOf(cart.neighborhood(), zones).orElse(null) : null;
        BigDecimal fee = zone == null ? BigDecimal.ZERO : zone.fee();
        return new Priced(lines, subtotal, zone, fee, subtotal.add(fee), problems);
    }

    /** Registered zone with that name, ignoring case and accents. */
    public static Optional<DeliveryZone> zoneOf(String neighborhood, List<DeliveryZone> zones) {
        String wanted = SearchText.normalize(neighborhood);
        return wanted.isEmpty() ? Optional.empty()
                : zones.stream().filter(zone -> SearchText.normalize(zone.neighborhood()).equals(wanted)).findFirst();
    }

    /**
     * Option of the item the customer meant: same name ignoring case and accents, the start of one
     * ("grande" for "Grande (12 fatias)"), or group and name ("borda catupiry").
     *
     * @return the option, or empty when none or several match
     */
    public static Optional<CatalogOption> optionOf(CatalogItem item, String wanted) {
        String normalized = SearchText.normalize(wanted);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        List<CatalogOption> exact = item.options().stream()
                .filter(option -> SearchText.normalize(option.name()).equals(normalized)
                        || SearchText.normalize(option.group() + " " + option.name()).equals(normalized))
                .toList();
        if (exact.size() == 1) {
            return Optional.of(exact.getFirst());
        }
        List<CatalogOption> prefix = item.options().stream()
                .filter(option -> SearchText.normalize(option.name()).startsWith(normalized)
                        || SearchText.normalize(option.group() + " " + option.name()).startsWith(normalized))
                .toList();
        return prefix.size() == 1 ? Optional.of(prefix.getFirst()) : Optional.empty();
    }
}
