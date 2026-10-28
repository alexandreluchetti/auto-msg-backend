package br.com.conversa.app.tenant;

import br.com.conversa.core.tenant.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds one tenant and its number at startup, until tenants are onboarded from the panel: the
 * pilot restaurant in production, the Cloud API test number in development. Off when
 * {@code conversa.bootstrap.slug} is empty. An invalid configuration stops the startup.
 */
@Component
class TenantBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantBootstrap.class);

    private final BootstrapProperties properties;
    private final TenantService tenants;
    private final TenantChannelService channels;

    TenantBootstrap(BootstrapProperties properties, TenantService tenants, TenantChannelService channels) {
        this.properties = properties;
        this.tenants = tenants;
        this.channels = channels;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (isBlank(properties.slug())) {
            return;
        }
        String config = isBlank(properties.configJson()) ? null : properties.configJson();
        TenantId tenant = tenants.findBySlug(properties.slug()).orElse(null);
        if (tenant == null) {
            tenant = tenants.create(properties.slug(), properties.displayName(), properties.segment(), config);
            log.info("Bootstrap created tenant {}", properties.slug());
        } else if (config != null) {
            tenants.updateConfig(tenant, config);
            log.info("Bootstrap updated the configuration of tenant {}", properties.slug());
        }
        if (!isBlank(properties.phoneNumberId())) {
            channels.register(tenant, properties.phoneNumberId(), blankToNull(properties.wabaId()),
                    blankToNull(properties.accessTokenRef()));
            log.info("Bootstrap registered a WhatsApp number for tenant {}", properties.slug());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

}
