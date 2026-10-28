package br.com.conversa.app.tenant;

import br.com.conversa.channel.whatsapp.WhatsAppProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Cloud API token of a sending number. The database stores only a reference (the name of a
 * property or environment variable), never the token. Numbers without a reference, or whose
 * reference is not set, use the platform token.
 */
@Component
public class ChannelAccessTokens {

    private final TenantChannelService channels;
    private final Environment environment;
    private final WhatsAppProperties whatsApp;

    public ChannelAccessTokens(TenantChannelService channels, Environment environment, WhatsAppProperties whatsApp) {
        this.channels = channels;
        this.environment = environment;
        this.whatsApp = whatsApp;
    }

    public String forSender(String phoneNumberId) {
        return channels.accessTokenRef(phoneNumberId)
                .map(environment::getProperty)
                .filter(token -> !token.isBlank())
                .orElse(whatsApp.accessToken());
    }
}
