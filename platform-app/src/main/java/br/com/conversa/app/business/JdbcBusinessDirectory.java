package br.com.conversa.app.business;

import br.com.conversa.app.tenant.TenantTransactions;
import br.com.conversa.core.business.BusinessDirectory;
import br.com.conversa.core.business.DeliveryZone;
import br.com.conversa.core.business.OpeningHours;
import br.com.conversa.core.business.OpeningPeriod;
import br.com.conversa.core.catalog.CatalogItem;
import br.com.conversa.core.catalog.CatalogOption;
import br.com.conversa.core.tenant.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Business data of a tenant, always read inside its tenant context (RLS, ADR-010). Queries also
 * filter by tenant, for the indexes.
 */
@Repository
class JdbcBusinessDirectory implements BusinessDirectory {

    private final JdbcClient jdbc;
    private final TenantTransactions tenants;

    JdbcBusinessDirectory(JdbcClient jdbc, TenantTransactions tenants) {
        this.jdbc = jdbc;
        this.tenants = tenants;
    }

    @Override
    public List<CatalogItem> catalog(TenantId tenant) {
        return tenants.inTenant(tenant, () -> {
            record OptionRow(UUID itemId, String groupName, String name, BigDecimal priceDelta) {
            }
            Map<UUID, List<CatalogOption>> options = jdbc.sql("""
                            SELECT item_id, group_name, name, price_delta FROM catalog_option
                            WHERE tenant_id = :tenant ORDER BY item_id, position
                            """)
                    .param("tenant", tenant.value())
                    .query(OptionRow.class).list().stream()
                    .collect(Collectors.groupingBy(OptionRow::itemId, Collectors.mapping(
                            row -> new CatalogOption(row.groupName(), row.name(), row.priceDelta()),
                            Collectors.toList())));
            return jdbc.sql("""
                            SELECT i.id, i.code, c.name AS category, i.name, i.description, i.price, i.available
                            FROM catalog_item i JOIN catalog_category c ON c.id = i.category_id
                            WHERE i.tenant_id = :tenant ORDER BY c.position, i.position
                            """)
                    .param("tenant", tenant.value())
                    .query((rs, row) -> {
                        UUID id = rs.getObject("id", UUID.class);
                        return new CatalogItem(id, rs.getString("code"), rs.getString("category"),
                                rs.getString("name"), rs.getString("description"), rs.getBigDecimal("price"),
                                rs.getBoolean("available"), options.getOrDefault(id, List.of()));
                    })
                    .list();
        });
    }

    @Override
    public OpeningHours openingHours(TenantId tenant) {
        return tenants.inTenant(tenant, () -> {
            String timezone = jdbc.sql("SELECT timezone FROM tenant WHERE id = :id")
                    .param("id", tenant.value())
                    .query(String.class).single();
            List<OpeningPeriod> periods = jdbc.sql("""
                            SELECT day_of_week, opens, closes FROM business_hours
                            WHERE tenant_id = :tenant ORDER BY day_of_week, opens
                            """)
                    .param("tenant", tenant.value())
                    .query((rs, row) -> new OpeningPeriod(DayOfWeek.of(rs.getInt("day_of_week")),
                            rs.getObject("opens", LocalTime.class), rs.getObject("closes", LocalTime.class)))
                    .list();
            return new OpeningHours(ZoneId.of(timezone), periods);
        });
    }

    @Override
    public List<DeliveryZone> deliveryZones(TenantId tenant) {
        return tenants.inTenant(tenant, () -> jdbc.sql("""
                        SELECT neighborhood, fee FROM delivery_zone WHERE tenant_id = :tenant ORDER BY position
                        """)
                .param("tenant", tenant.value())
                .query(DeliveryZone.class).list());
    }
}
