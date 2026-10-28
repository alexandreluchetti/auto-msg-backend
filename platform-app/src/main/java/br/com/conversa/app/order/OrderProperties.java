package br.com.conversa.app.order;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Confirmed orders (D-004).
 *
 * @param staffAlertTemplate approved utility template that tells staff numbers about a new order,
 *                           with four body parameters (number, customer, delivery or pickup, total);
 *                           empty sends no WhatsApp alert
 * @param staffAlertLanguage language code of that template
 */
@ConfigurationProperties("conversa.orders")
public record OrderProperties(String staffAlertTemplate, @DefaultValue("pt_BR") String staffAlertLanguage) {
}
