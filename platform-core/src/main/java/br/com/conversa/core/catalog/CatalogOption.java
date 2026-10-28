package br.com.conversa.core.catalog;

import java.math.BigDecimal;

/**
 * A choice on an item, e.g. group "Tamanho", name "Grande", price delta 10.00.
 */
public record CatalogOption(String group, String name, BigDecimal priceDelta) {
}
