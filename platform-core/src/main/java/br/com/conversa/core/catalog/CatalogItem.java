package br.com.conversa.core.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Something a business sells, as registered by the business. The only source of its price.
 *
 * @param code     the business's own code, stable across imports
 * @param category name of the category, e.g. "Pizzas"
 */
public record CatalogItem(
        UUID id,
        String code,
        String category,
        String name,
        String description,
        BigDecimal price,
        boolean available,
        List<CatalogOption> options) {

    public CatalogItem {
        options = List.copyOf(options);
    }
}
