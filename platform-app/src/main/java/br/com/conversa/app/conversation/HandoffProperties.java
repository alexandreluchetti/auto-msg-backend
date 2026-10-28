package br.com.conversa.app.conversation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Human handoff (ADR-008, D-003, D-004).
 *
 * @param failureThreshold   consecutive answers the bot could not give before it hands over
 * @param staffAlertTemplate approved utility template that alerts staff numbers of a handoff, with
 *                           two body parameters (customer, reason); empty sends no WhatsApp alert
 * @param staffAlertLanguage language code of that template
 */
@ConfigurationProperties("conversa.handoff")
public record HandoffProperties(
        @DefaultValue("2") int failureThreshold,
        String staffAlertTemplate,
        @DefaultValue("pt_BR") String staffAlertLanguage) {
}
