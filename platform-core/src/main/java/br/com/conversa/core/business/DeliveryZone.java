package br.com.conversa.core.business;

import java.math.BigDecimal;

/**
 * A neighborhood the business delivers to, with its delivery fee.
 */
public record DeliveryZone(String neighborhood, BigDecimal fee) {
}
