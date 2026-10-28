package br.com.conversa.core.business;

import br.com.conversa.core.catalog.CatalogItem;
import br.com.conversa.core.tenant.TenantId;

import java.util.List;

/**
 * Read port for the structured data a business registers (ADR-006): catalog, opening hours and
 * delivery zones. Tools read business facts only from here, never from the prompt.
 */
public interface BusinessDirectory {

    /** Every item, available or not, in the business's menu order. */
    List<CatalogItem> catalog(TenantId tenant);

    OpeningHours openingHours(TenantId tenant);

    List<DeliveryZone> deliveryZones(TenantId tenant);
}
