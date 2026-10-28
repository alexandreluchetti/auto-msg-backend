package br.com.conversa.app.tenant;

import br.com.conversa.app.business.BusinessDataImporter;
import br.com.conversa.core.tenant.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Seeds one tenant and its number at startup, until tenants are onboarded from the panel: the
 * pilot restaurant in production, the Cloud API test number in development. Off when
 * {@code conversa.bootstrap.slug} is empty. Spreadsheets in {@code conversa.bootstrap.data-dir}
 * load the tenant's menu, opening hours and delivery zones. An invalid configuration or spreadsheet
 * stops the startup.
 */
@Component
class TenantBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantBootstrap.class);

    private final BootstrapProperties properties;
    private final TenantService tenants;
    private final TenantChannelService channels;
    private final BusinessDataImporter importer;

    TenantBootstrap(BootstrapProperties properties, TenantService tenants, TenantChannelService channels,
                    BusinessDataImporter importer) {
        this.properties = properties;
        this.tenants = tenants;
        this.channels = channels;
        this.importer = importer;
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
        if (!isBlank(properties.dataDir())) {
            importData(tenant, Path.of(properties.dataDir()));
        }
    }

    private void importData(TenantId tenant, Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("Bootstrap data folder does not exist: " + dir);
        }
        String menu = read(dir.resolve(BusinessDataImporter.MENU_FILE));
        if (menu != null) {
            var imported = importer.importMenu(tenant, menu, read(dir.resolve(BusinessDataImporter.OPTIONS_FILE)));
            log.info("Bootstrap imported {} items in {} categories, with {} options, for tenant {}",
                    imported.items(), imported.categories(), imported.options(), properties.slug());
        }
        String hours = read(dir.resolve(BusinessDataImporter.HOURS_FILE));
        if (hours != null) {
            log.info("Bootstrap imported {} opening periods for tenant {}",
                    importer.importOpeningHours(tenant, hours), properties.slug());
        }
        String zones = read(dir.resolve(BusinessDataImporter.ZONES_FILE));
        if (zones != null) {
            log.info("Bootstrap imported {} delivery zones for tenant {}",
                    importer.importDeliveryZones(tenant, zones), properties.slug());
        }
    }

    private static String read(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

}
