package br.com.conversa.channel.whatsapp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration of the WhatsApp Cloud API integration. See ADR-001.
 *
 * @param verifyToken    token compared in the webhook verification GET
 * @param appSecret      Meta app secret, used in the HMAC of {@code X-Hub-Signature-256}
 * @param graphApiUrl    Graph API base URL
 * @param graphApiVersion Graph API version, e.g. {@code v24.0}
 */
@ConfigurationProperties("conversa.whatsapp")
public record WhatsAppProperties(
        String verifyToken,
        String appSecret,
        String graphApiUrl,
        String graphApiVersion) {
}
