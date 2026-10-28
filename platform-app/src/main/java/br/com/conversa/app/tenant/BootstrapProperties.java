package br.com.conversa.app.tenant;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tenant seeded at startup by {@link TenantBootstrap}.
 *
 * @param slug           tenant to create on first start; empty turns the bootstrap off
 * @param displayName    tenant name shown in the panel
 * @param segment        Segment Pack id
 * @param configJson     tenant settings (JSON object) merged over the pack defaults; when set, it
 *                       replaces the stored settings on every start
 * @param phoneNumberId  Cloud API {@code phone_number_id} of the tenant's number
 * @param wabaId         WhatsApp Business Account id
 * @param accessTokenRef property/environment variable with that number's token; empty uses the
 *                       platform token
 */
@ConfigurationProperties("conversa.bootstrap")
public record BootstrapProperties(
        String slug,
        String displayName,
        @DefaultValue("restaurant") String segment,
        String configJson,
        String phoneNumberId,
        String wabaId,
        String accessTokenRef) {
}
