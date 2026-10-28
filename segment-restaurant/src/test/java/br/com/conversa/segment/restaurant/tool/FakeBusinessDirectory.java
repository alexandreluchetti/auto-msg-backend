package br.com.conversa.segment.restaurant.tool;

import br.com.conversa.core.business.BusinessDirectory;
import br.com.conversa.core.business.DeliveryZone;
import br.com.conversa.core.business.OpeningHours;
import br.com.conversa.core.catalog.CatalogItem;
import br.com.conversa.core.tenant.TenantId;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-memory directory: each tenant has its own data, nothing registered by default. */
public final class FakeBusinessDirectory implements BusinessDirectory {

    private final Map<TenantId, List<CatalogItem>> catalogs = new HashMap<>();
    private final Map<TenantId, OpeningHours> hours = new HashMap<>();
    private final Map<TenantId, List<DeliveryZone>> zones = new HashMap<>();

    FakeBusinessDirectory catalog(TenantId tenant, CatalogItem... items) {
        catalogs.put(tenant, List.of(items));
        return this;
    }

    FakeBusinessDirectory hours(TenantId tenant, OpeningHours openingHours) {
        hours.put(tenant, openingHours);
        return this;
    }

    FakeBusinessDirectory zones(TenantId tenant, DeliveryZone... deliveryZones) {
        zones.put(tenant, List.of(deliveryZones));
        return this;
    }

    @Override
    public List<CatalogItem> catalog(TenantId tenant) {
        return catalogs.getOrDefault(tenant, List.of());
    }

    @Override
    public OpeningHours openingHours(TenantId tenant) {
        return hours.getOrDefault(tenant, new OpeningHours(ZoneId.of("America/Sao_Paulo"), List.of()));
    }

    @Override
    public List<DeliveryZone> deliveryZones(TenantId tenant) {
        return zones.getOrDefault(tenant, List.of());
    }
}
