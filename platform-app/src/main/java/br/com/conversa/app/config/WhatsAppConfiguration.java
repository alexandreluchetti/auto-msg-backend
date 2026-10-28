package br.com.conversa.app.config;

import br.com.conversa.channel.whatsapp.WhatsAppProperties;
import br.com.conversa.channel.whatsapp.webhook.WebhookPayloadParser;
import br.com.conversa.channel.whatsapp.webhook.WebhookSignatureVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class WhatsAppConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppConfiguration.class);

    @Bean
    WebhookSignatureVerifier webhookSignatureVerifier(WhatsAppProperties properties) {
        if (properties.appSecret() == null || properties.appSecret().isBlank()) {
            log.warn("conversa.whatsapp.app-secret not set: every webhook POST will be refused");
        }
        return new WebhookSignatureVerifier(properties.appSecret());
    }

    @Bean
    WebhookPayloadParser webhookPayloadParser(ObjectMapper objectMapper) {
        return new WebhookPayloadParser(objectMapper);
    }
}
