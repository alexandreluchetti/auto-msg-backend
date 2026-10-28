package br.com.conversa.app.config;

import br.com.conversa.app.conversation.AssistantProperties;
import br.com.conversa.app.conversation.HandoffProperties;
import br.com.conversa.app.conversation.LlmPricing;
import br.com.conversa.app.tenant.BootstrapProperties;
import br.com.conversa.app.tenant.TenantService;
import br.com.conversa.channel.whatsapp.WhatsAppProperties;
import br.com.conversa.core.business.BusinessDirectory;
import br.com.conversa.core.llm.LlmClient;
import br.com.conversa.core.segment.SegmentPack;
import br.com.conversa.core.tenant.TenantSettings;
import br.com.conversa.llm.anthropic.AnthropicLlmClient;
import br.com.conversa.llm.anthropic.AnthropicProperties;
import br.com.conversa.segment.restaurant.RestaurantSegmentPack;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Module wiring: adapters and segment packs do not know each other; they are connected here.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({WhatsAppProperties.class, AnthropicProperties.class, BootstrapProperties.class,
        AssistantProperties.class, LlmPricing.class, HandoffProperties.class})
class PlatformConfiguration {

    @Bean
    LlmClient llmClient(AnthropicProperties properties) {
        return new AnthropicLlmClient(properties);
    }

    /** Lazy, because tenant settings depend on the pack registry, which holds the packs. */
    @Bean
    TenantSettings tenantSettings(ObjectProvider<TenantService> tenants) {
        return tenant -> tenants.getObject().effectiveConfig(tenant).toString();
    }

    @Bean
    SegmentPack restaurantSegmentPack(BusinessDirectory directory, TenantSettings settings, Clock clock) {
        return new RestaurantSegmentPack(directory, settings, clock);
    }
}
