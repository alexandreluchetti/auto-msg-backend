package br.com.conversa.app.config;

import br.com.conversa.channel.whatsapp.WhatsAppProperties;
import br.com.conversa.channel.whatsapp.send.WhatsAppCloudApiGateway;
import br.com.conversa.channel.whatsapp.webhook.WebhookPayloadParser;
import br.com.conversa.channel.whatsapp.webhook.WebhookSignatureVerifier;
import br.com.conversa.core.channel.ChannelGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
class WhatsAppConfiguration {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppConfiguration.class);

    @Bean
    WebhookSignatureVerifier webhookSignatureVerifier(WhatsAppProperties properties) {
        if (isBlank(properties.appSecret())) {
            log.warn("conversa.whatsapp.app-secret not set: every webhook POST will be refused");
        }
        return new WebhookSignatureVerifier(properties.appSecret());
    }

    @Bean
    WebhookPayloadParser webhookPayloadParser(ObjectMapper objectMapper) {
        return new WebhookPayloadParser(objectMapper);
    }

    @Bean
    ChannelGateway whatsAppGateway(WhatsAppProperties properties, ObjectMapper objectMapper) {
        if (isBlank(properties.accessToken())) {
            log.warn("conversa.whatsapp.access-token not set: outbound messages will fail");
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return new WhatsAppCloudApiGateway(properties.graphApiUrl(), properties.graphApiVersion(),
                properties.accessToken(), http, objectMapper);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
