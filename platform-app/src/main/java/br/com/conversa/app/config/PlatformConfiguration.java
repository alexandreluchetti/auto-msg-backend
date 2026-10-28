package br.com.conversa.app.config;

import br.com.conversa.app.tenant.BootstrapProperties;
import br.com.conversa.channel.whatsapp.WhatsAppProperties;
import br.com.conversa.core.segment.SegmentPack;
import br.com.conversa.llm.anthropic.AnthropicProperties;
import br.com.conversa.segment.restaurant.RestaurantSegmentPack;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Module wiring: adapters and segment packs do not know each other; they are connected here.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({WhatsAppProperties.class, AnthropicProperties.class, BootstrapProperties.class})
class PlatformConfiguration {

    @Bean
    SegmentPack restaurantSegmentPack() {
        return new RestaurantSegmentPack();
    }
}
