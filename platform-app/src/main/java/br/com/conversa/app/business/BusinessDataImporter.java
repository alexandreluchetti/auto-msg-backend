package br.com.conversa.app.business;

import br.com.conversa.app.tenant.TenantTransactions;
import br.com.conversa.core.tenant.TenantId;
import br.com.conversa.core.text.SearchText;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Loads a tenant's catalog, opening hours and delivery zones from spreadsheets (CSV), until the
 * panel edits them. Each import validates the whole file first and stores nothing if any line is
 * wrong; then it replaces that data set in one transaction of the tenant. Items keep their ids
 * across imports (matched by {@code code}); items missing from a new menu are removed.
 */
@Service
public class BusinessDataImporter {

    public static final String MENU_FILE = "menu.csv";
    public static final String OPTIONS_FILE = "options.csv";
    public static final String HOURS_FILE = "hours.csv";
    public static final String ZONES_FILE = "zones.csv";

    private static final Pattern TIME = Pattern.compile("^\\d{1,2}:\\d{2}$");
    private static final Map<String, DayOfWeek> DAYS = Map.ofEntries(
            Map.entry("mon", DayOfWeek.MONDAY), Map.entry("tue", DayOfWeek.TUESDAY),
            Map.entry("wed", DayOfWeek.WEDNESDAY), Map.entry("thu", DayOfWeek.THURSDAY),
            Map.entry("fri", DayOfWeek.FRIDAY), Map.entry("sat", DayOfWeek.SATURDAY),
            Map.entry("sun", DayOfWeek.SUNDAY));

    public record MenuImport(int categories, int items, int options) {
    }

    private record ItemRow(String code, String category, String name, String description, BigDecimal price,
                           boolean available) {
    }

    private record OptionRow(String itemCode, String group, String name, BigDecimal priceDelta) {
    }

    private record PeriodRow(DayOfWeek day, LocalTime opens, LocalTime closes) {
    }

    private record ZoneRow(String neighborhood, BigDecimal fee) {
    }

    private final JdbcClient jdbc;
    private final TenantTransactions tenants;

    public BusinessDataImporter(JdbcClient jdbc, TenantTransactions tenants) {
        this.jdbc = jdbc;
        this.tenants = tenants;
    }

    /**
     * Replaces the menu and its options.
     *
     * @param optionsCsv options of the items; {@code null} leaves every item without options
     * @throws CsvImportException if a file is invalid; nothing changes
     */
    public MenuImport importMenu(TenantId tenant, String menuCsv, String optionsCsv) {
        List<ItemRow> items = parseMenu(menuCsv);
        Set<String> codes = new HashSet<>();
        items.forEach(item -> codes.add(item.code()));
        List<OptionRow> options = optionsCsv == null ? List.of() : parseOptions(optionsCsv, codes);

        return tenants.inTenant(tenant, () -> {
            Map<String, UUID> categoryIds = new LinkedHashMap<>();
            for (ItemRow item : items) {
                categoryIds.computeIfAbsent(item.category(), name -> jdbc.sql("""
                                INSERT INTO catalog_category (tenant_id, name, position)
                                VALUES (:tenant, :name, :position)
                                ON CONFLICT (tenant_id, name) DO UPDATE SET position = excluded.position, updated_at = now()
                                RETURNING id
                                """)
                        .param("tenant", tenant.value())
                        .param("name", name)
                        .param("position", categoryIds.size())
                        .query(UUID.class).single());
            }
            Map<String, UUID> itemIds = new HashMap<>();
            for (int position = 0; position < items.size(); position++) {
                ItemRow item = items.get(position);
                itemIds.put(item.code(), jdbc.sql("""
                                INSERT INTO catalog_item (tenant_id, category_id, code, name, description, price, available, position)
                                VALUES (:tenant, :category, :code, :name, :description, :price, :available, :position)
                                ON CONFLICT (tenant_id, code) DO UPDATE SET
                                    category_id = excluded.category_id, name = excluded.name,
                                    description = excluded.description, price = excluded.price,
                                    available = excluded.available, position = excluded.position, updated_at = now()
                                RETURNING id
                                """)
                        .param("tenant", tenant.value())
                        .param("category", categoryIds.get(item.category()))
                        .param("code", item.code())
                        .param("name", item.name())
                        .param("description", item.description())
                        .param("price", item.price())
                        .param("available", item.available())
                        .param("position", position)
                        .query(UUID.class).single());
            }
            jdbc.sql("DELETE FROM catalog_item WHERE tenant_id = :tenant AND NOT (code IN (:codes))")
                    .param("tenant", tenant.value())
                    .param("codes", List.copyOf(codes))
                    .update();
            jdbc.sql("DELETE FROM catalog_category WHERE tenant_id = :tenant AND NOT (name IN (:names))")
                    .param("tenant", tenant.value())
                    .param("names", List.copyOf(categoryIds.keySet()))
                    .update();
            jdbc.sql("DELETE FROM catalog_option WHERE tenant_id = :tenant").param("tenant", tenant.value()).update();
            for (int position = 0; position < options.size(); position++) {
                OptionRow option = options.get(position);
                jdbc.sql("""
                                INSERT INTO catalog_option (tenant_id, item_id, group_name, name, price_delta, position)
                                VALUES (:tenant, :item, :group, :name, :priceDelta, :position)
                                """)
                        .param("tenant", tenant.value())
                        .param("item", itemIds.get(option.itemCode()))
                        .param("group", option.group())
                        .param("name", option.name())
                        .param("priceDelta", option.priceDelta())
                        .param("position", position)
                        .update();
            }
            return new MenuImport(categoryIds.size(), items.size(), options.size());
        });
    }

    /**
     * Replaces the weekly opening hours.
     *
     * @return number of periods stored
     * @throws CsvImportException if the file is invalid; nothing changes
     */
    public int importOpeningHours(TenantId tenant, String hoursCsv) {
        List<PeriodRow> periods = parseHours(hoursCsv);
        return tenants.inTenant(tenant, () -> {
            jdbc.sql("DELETE FROM business_hours WHERE tenant_id = :tenant").param("tenant", tenant.value()).update();
            for (PeriodRow period : periods) {
                jdbc.sql("""
                                INSERT INTO business_hours (tenant_id, day_of_week, opens, closes)
                                VALUES (:tenant, :day, :opens, :closes)
                                """)
                        .param("tenant", tenant.value())
                        .param("day", period.day().getValue())
                        .param("opens", period.opens())
                        .param("closes", period.closes())
                        .update();
            }
            return periods.size();
        });
    }

    /**
     * Replaces the delivery zones.
     *
     * @return number of zones stored
     * @throws CsvImportException if the file is invalid; nothing changes
     */
    public int importDeliveryZones(TenantId tenant, String zonesCsv) {
        List<ZoneRow> zones = parseZones(zonesCsv);
        return tenants.inTenant(tenant, () -> {
            jdbc.sql("DELETE FROM delivery_zone WHERE tenant_id = :tenant").param("tenant", tenant.value()).update();
            for (int position = 0; position < zones.size(); position++) {
                ZoneRow zone = zones.get(position);
                jdbc.sql("""
                                INSERT INTO delivery_zone (tenant_id, neighborhood, fee, position)
                                VALUES (:tenant, :neighborhood, :fee, :position)
                                """)
                        .param("tenant", tenant.value())
                        .param("neighborhood", zone.neighborhood())
                        .param("fee", zone.fee())
                        .param("position", position)
                        .update();
            }
            return zones.size();
        });
    }

    private static List<ItemRow> parseMenu(String csv) {
        CsvTable table = CsvTable.parse(MENU_FILE, csv, Set.of("code", "category", "name", "price"),
                Set.of("description", "available"));
        List<String> errors = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        List<ItemRow> items = new ArrayList<>();
        for (CsvTable.Row row : table.rows()) {
            String code = required(row, "code", errors);
            String category = required(row, "category", errors);
            String name = required(row, "name", errors);
            BigDecimal price = money(row, "price", true, errors);
            Boolean available = flag(row, "available", errors);
            if (code != null && !codes.add(code)) {
                errors.add("line " + row.line() + ": code '" + code + "' is repeated");
            }
            if (code != null && category != null && name != null && price != null && available != null) {
                String description = row.get("description");
                items.add(new ItemRow(code, category, name, description.isEmpty() ? null : description, price, available));
            }
        }
        if (table.rows().isEmpty()) {
            errors.add("the menu has no items");
        }
        return valid(MENU_FILE, errors, items);
    }

    private static List<OptionRow> parseOptions(String csv, Set<String> itemCodes) {
        CsvTable table = CsvTable.parse(OPTIONS_FILE, csv, Set.of("item_code", "group", "name"), Set.of("price_delta"));
        List<String> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<OptionRow> options = new ArrayList<>();
        for (CsvTable.Row row : table.rows()) {
            String itemCode = required(row, "item_code", errors);
            String group = required(row, "group", errors);
            String name = required(row, "name", errors);
            BigDecimal delta = money(row, "price_delta", false, errors);
            if (itemCode != null && !itemCodes.contains(itemCode)) {
                errors.add("line " + row.line() + ": item_code '" + itemCode + "' is not in " + MENU_FILE);
            } else if (itemCode != null && group != null && name != null && delta != null) {
                if (!seen.add(itemCode + "\n" + group + "\n" + name)) {
                    errors.add("line " + row.line() + ": option '" + group + " / " + name + "' is repeated for item " + itemCode);
                } else {
                    options.add(new OptionRow(itemCode, group, name, delta));
                }
            }
        }
        return valid(OPTIONS_FILE, errors, options);
    }

    private static List<PeriodRow> parseHours(String csv) {
        CsvTable table = CsvTable.parse(HOURS_FILE, csv, Set.of("day", "opens", "closes"), Set.of());
        List<String> errors = new ArrayList<>();
        List<PeriodRow> periods = new ArrayList<>();
        for (CsvTable.Row row : table.rows()) {
            DayOfWeek day = day(row, errors);
            LocalTime opens = time(row, "opens", errors);
            LocalTime closes = time(row, "closes", errors);
            if (opens != null && opens.equals(closes)) {
                errors.add("line " + row.line() + ": opens and closes are the same time");
            } else if (day != null && opens != null && closes != null) {
                periods.add(new PeriodRow(day, opens, closes));
            }
        }
        return valid(HOURS_FILE, errors, periods);
    }

    private static List<ZoneRow> parseZones(String csv) {
        CsvTable table = CsvTable.parse(ZONES_FILE, csv, Set.of("neighborhood", "fee"), Set.of());
        List<String> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<ZoneRow> zones = new ArrayList<>();
        for (CsvTable.Row row : table.rows()) {
            String neighborhood = required(row, "neighborhood", errors);
            BigDecimal fee = money(row, "fee", true, errors);
            if (neighborhood != null && !seen.add(SearchText.normalize(neighborhood))) {
                errors.add("line " + row.line() + ": neighborhood '" + neighborhood + "' is repeated");
            } else if (neighborhood != null && fee != null) {
                zones.add(new ZoneRow(neighborhood, fee));
            }
        }
        return valid(ZONES_FILE, errors, zones);
    }

    private static <T> List<T> valid(String file, List<String> errors, List<T> rows) {
        if (!errors.isEmpty()) {
            throw new CsvImportException(file, errors);
        }
        return rows;
    }

    private static String required(CsvTable.Row row, String column, List<String> errors) {
        String value = row.get(column);
        if (value.isEmpty()) {
            errors.add("line " + row.line() + ": " + column + " is required");
            return null;
        }
        return value;
    }

    /**
     * Amount in reais: "39.90", "39,90", "R$ 1.234,50". Two decimal places at most.
     *
     * @param required when false, an empty value is zero
     */
    static BigDecimal money(CsvTable.Row row, String column, boolean required, List<String> errors) {
        String raw = row.get(column).replace("R$", "").replace(" ", "");
        if (raw.isEmpty()) {
            if (required) {
                errors.add("line " + row.line() + ": " + column + " is required");
                return null;
            }
            return BigDecimal.ZERO;
        }
        String number = raw.contains(",") ? raw.replace(".", "").replace(',', '.') : raw;
        try {
            BigDecimal amount = new BigDecimal(number);
            if (amount.signum() < 0 || amount.scale() > 2) {
                errors.add("line " + row.line() + ": " + column + " must be zero or more with at most 2 decimals, got '" + row.get(column) + "'");
                return null;
            }
            return amount;
        } catch (NumberFormatException e) {
            errors.add("line " + row.line() + ": " + column + " must be an amount like 39,90, got '" + row.get(column) + "'");
            return null;
        }
    }

    /** Empty means yes. */
    private static Boolean flag(CsvTable.Row row, String column, List<String> errors) {
        return switch (row.get(column).toLowerCase(Locale.ROOT)) {
            case "", "true", "yes", "y", "1", "sim", "s" -> true;
            case "false", "no", "n", "0", "não", "nao" -> false;
            default -> {
                errors.add("line " + row.line() + ": " + column + " must be yes or no, got '" + row.get(column) + "'");
                yield null;
            }
        };
    }

    /** mon…sun, or 1 (Monday) to 7 (Sunday). */
    private static DayOfWeek day(CsvTable.Row row, List<String> errors) {
        String value = row.get("day").toLowerCase(Locale.ROOT);
        if (DAYS.containsKey(value)) {
            return DAYS.get(value);
        }
        if (value.matches("[1-7]")) {
            return DayOfWeek.of(Integer.parseInt(value));
        }
        errors.add("line " + row.line() + ": day must be mon, tue, wed, thu, fri, sat, sun or 1-7, got '" + row.get("day") + "'");
        return null;
    }

    private static LocalTime time(CsvTable.Row row, String column, List<String> errors) {
        String value = row.get(column);
        try {
            if (TIME.matcher(value).matches()) {
                return LocalTime.parse(value.length() == 4 ? "0" + value : value);
            }
        } catch (DateTimeParseException e) {
            // reported below
        }
        errors.add("line " + row.line() + ": " + column + " must be a time like 18:00, got '" + value + "'");
        return null;
    }
}
