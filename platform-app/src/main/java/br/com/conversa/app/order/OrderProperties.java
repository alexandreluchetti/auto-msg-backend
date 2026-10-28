package br.com.conversa.app.order;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;

/**
 * Confirmed orders (D-004, D-011).
 *
 * @param staffAlertTemplate approved utility template that tells staff numbers about a new order,
 *                           with four body parameters (number, customer, delivery or pickup, total);
 *                           empty sends no WhatsApp alert
 * @param staffAlertLanguage language code of the templates
 * @param statusTemplates    approved utility template per status ({@code PREPARING},
 *                           {@code OUT_FOR_DELIVERY}, {@code READY_FOR_PICKUP}, {@code COMPLETED},
 *                           {@code CANCELLED}) with the order number as body parameter, used when the
 *                           24h window is closed; a status without one is not notified then
 */
@ConfigurationProperties("conversa.orders")
public record OrderProperties(String staffAlertTemplate, @DefaultValue("pt_BR") String staffAlertLanguage,
                              Map<String, String> statusTemplates) {

    public OrderProperties {
        statusTemplates = statusTemplates == null ? Map.of() : Map.copyOf(statusTemplates);
    }
}
