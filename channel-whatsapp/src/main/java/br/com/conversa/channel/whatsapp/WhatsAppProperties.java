package br.com.conversa.channel.whatsapp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuração da integração com a WhatsApp Cloud API. Ver ADR-001.
 *
 * @param verifyToken    token comparado no GET de verificação do webhook
 * @param appSecret      segredo do app Meta, usado no HMAC de {@code X-Hub-Signature-256}
 * @param graphApiUrl    base da Graph API
 * @param graphApiVersion versão da Graph API, ex.: {@code v24.0}
 */
@ConfigurationProperties("conversa.whatsapp")
public record WhatsAppProperties(
        String verifyToken,
        String appSecret,
        String graphApiUrl,
        String graphApiVersion) {
}
