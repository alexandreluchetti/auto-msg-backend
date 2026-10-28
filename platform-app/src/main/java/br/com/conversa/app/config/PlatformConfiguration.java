package br.com.conversa.app.config;

import br.com.conversa.channel.whatsapp.WhatsAppProperties;
import br.com.conversa.core.segment.SegmentPack;
import br.com.conversa.llm.anthropic.AnthropicProperties;
import br.com.conversa.segment.restaurant.RestaurantSegmentPack;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Composição dos módulos: adaptadores e segment packs não se conhecem; são ligados aqui.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({WhatsAppProperties.class, AnthropicProperties.class})
class PlatformConfiguration {

    @Bean
    SegmentPack restaurantSegmentPack() {
        return new RestaurantSegmentPack();
    }
}
