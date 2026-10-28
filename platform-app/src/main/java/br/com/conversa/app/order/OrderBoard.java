package br.com.conversa.app.order;

import br.com.conversa.app.api.PanelEvent;
import br.com.conversa.app.outbound.CustomerServiceWindow;
import br.com.conversa.app.outbound.OutboundMessageRepository;
import br.com.conversa.app.outbound.OutboundRequest;
import br.com.conversa.core.tenant.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orders as the panel sees and moves them (M8). Every status change notifies the customer (D-011):
 * free text inside the 24h window, otherwise the status's approved template, if configured. Must run
 * inside the tenant's transaction.
 */
@Service
public class OrderBoard {

    private static final Logger log = LoggerFactory.getLogger(OrderBoard.class);

    public enum Status { RECEIVED, PREPARING, OUT_FOR_DELIVERY, READY_FOR_PICKUP, COMPLETED, CANCELLED }

    private static final Map<Status, Set<Status>> NEXT = Map.of(
            Status.RECEIVED, Set.of(Status.PREPARING, Status.OUT_FOR_DELIVERY, Status.READY_FOR_PICKUP, Status.CANCELLED),
            Status.PREPARING, Set.of(Status.OUT_FOR_DELIVERY, Status.READY_FOR_PICKUP, Status.CANCELLED),
            Status.OUT_FOR_DELIVERY, Set.of(Status.COMPLETED, Status.CANCELLED),
            Status.READY_FOR_PICKUP, Set.of(Status.COMPLETED, Status.CANCELLED),
            Status.COMPLETED, Set.of(),
            Status.CANCELLED, Set.of());

    public record Item(String name, int quantity, BigDecimal unitPrice, List<String> options, String notes,
                       BigDecimal lineTotal) {
    }

    public record OrderView(UUID id, int number, String status, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                            String customerName, String customerPhone, String fulfillment, String address,
                            String neighborhood, BigDecimal deliveryFee, String paymentMethod, BigDecimal changeFor,
                            BigDecimal subtotal, BigDecimal total, List<Item> items) {
    }

    /** The change is not allowed from the order's current status. */
    public static class InvalidTransition extends RuntimeException {
        InvalidTransition(String message) {
            super(message);
        }
    }

    private final JdbcClient jdbc;
    private final OutboundMessageRepository outbound;
    private final CustomerServiceWindow window;
    private final OrderProperties properties;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper;

    OrderBoard(JdbcClient jdbc, OutboundMessageRepository outbound, CustomerServiceWindow window,
               OrderProperties properties, ApplicationEventPublisher events, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.outbound = outbound;
        this.window = window;
        this.properties = properties;
        this.events = events;
        this.mapper = mapper;
    }

    /**
     * @param open true for orders still in progress; false for every order of the last 24 hours
     */
    public List<OrderView> list(TenantId tenant, boolean open) {
        String filter = open ? "o.status NOT IN ('COMPLETED', 'CANCELLED')" : "o.created_at > now() - interval '24 hours'";
        return query(tenant, filter + " ORDER BY o.number DESC", Map.of());
    }

    public Optional<OrderView> find(TenantId tenant, UUID id) {
        return query(tenant, "o.id = :id", Map.of("id", id)).stream().findFirst();
    }

    /**
     * Moves the order and tells the customer.
     *
     * @throws InvalidTransition when the order cannot go there from its current status
     */
    public OrderView move(TenantId tenant, UUID id, Status target) {
        record Current(String status, int number, String fulfillment, String phoneNumberId, String waId) {
        }
        Current current = jdbc.sql("""
                        SELECT o.status, o.number, o.fulfillment, c.phone_number_id, k.wa_id
                        FROM customer_order o
                        JOIN conversation c ON c.id = o.conversation_id JOIN contact k ON k.id = o.contact_id
                        WHERE o.id = :id AND o.tenant_id = :tenant
                        FOR UPDATE OF o
                        """)
                .param("id", id).param("tenant", tenant.value())
                .query(Current.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("Unknown order " + id));
        Status from = Status.valueOf(current.status());
        if (!NEXT.get(from).contains(target)) {
            throw new InvalidTransition("Order #" + current.number() + " cannot go from " + from + " to " + target);
        }
        if (target == Status.OUT_FOR_DELIVERY && "PICKUP".equals(current.fulfillment())
                || target == Status.READY_FOR_PICKUP && "DELIVERY".equals(current.fulfillment())) {
            throw new InvalidTransition("Order #" + current.number() + " is " + current.fulfillment());
        }
        jdbc.sql("UPDATE customer_order SET status = :status, updated_at = now() WHERE id = :id")
                .param("status", target.name()).param("id", id).update();
        notifyCustomer(tenant, id, current.number(), target, current.phoneNumberId(), current.waId());
        events.publishEvent(PanelEvent.orderUpdated(tenant, id));
        return find(tenant, id).orElseThrow();
    }

    static String notice(int number, Status status) {
        return switch (status) {
            case PREPARING -> "Seu pedido *#" + number + "* já está sendo preparado!";
            case OUT_FOR_DELIVERY -> "Seu pedido *#" + number + "* saiu para entrega!";
            case READY_FOR_PICKUP -> "Seu pedido *#" + number + "* está pronto para retirada!";
            case COMPLETED -> "Pedido *#" + number + "* concluído. Obrigado pela preferência!";
            case CANCELLED -> "Seu pedido *#" + number + "* foi cancelado. Se tiver alguma dúvida, é só responder por aqui.";
            case RECEIVED -> throw new IllegalArgumentException("No notice for " + status);
        };
    }

    private void notifyCustomer(TenantId tenant, UUID order, int number, Status status, String phoneNumberId,
                                String waId) {
        String key = "status:" + order + ":" + status;
        if (window.isOpen(phoneNumberId, waId)) {
            outbound.enqueue(key, tenant, OutboundRequest.text(phoneNumberId, waId, notice(number, status), mapper));
            return;
        }
        String template = properties.statusTemplates().get(status.name());
        if (template == null || template.isBlank()) {
            log.warn("Order #{} of tenant {}: 24h window closed and no template for {}; the customer is not notified",
                    number, tenant, status);
            return;
        }
        outbound.enqueue(key, tenant, OutboundRequest.template(phoneNumberId, waId, template,
                properties.staffAlertLanguage(), List.of("#" + number), mapper));
    }

    private List<OrderView> query(TenantId tenant, String where, Map<String, Object> params) {
        record Row(UUID id, int number, String status, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                   String displayName, String waId, String fulfillment, String address, String neighborhood,
                   BigDecimal deliveryFee, String paymentMethod, BigDecimal changeFor, BigDecimal subtotal,
                   BigDecimal total) {
        }
        var statement = jdbc.sql("""
                        SELECT o.id, o.number, o.status, o.created_at, o.updated_at, k.display_name, k.wa_id,
                               o.fulfillment, o.address, o.neighborhood, o.delivery_fee, o.payment_method,
                               o.change_for, o.subtotal, o.total
                        FROM customer_order o JOIN contact k ON k.id = o.contact_id
                        WHERE o.tenant_id = :tenant AND """ + " " + where)
                .param("tenant", tenant.value());
        for (var param : params.entrySet()) {
            statement = statement.param(param.getKey(), param.getValue());
        }
        List<Row> rows = statement.query(Row.class).list();
        if (rows.isEmpty()) {
            return List.of();
        }
        record ItemRow(UUID orderId, String name, int quantity, BigDecimal unitPrice, String options, String notes,
                       BigDecimal lineTotal) {
        }
        Map<UUID, List<Item>> items = jdbc.sql("""
                        SELECT order_id, name, quantity, unit_price, options::text AS options, notes, line_total
                        FROM order_item WHERE order_id IN (:orders) ORDER BY order_id, position
                        """)
                .param("orders", rows.stream().map(Row::id).toList())
                .query(ItemRow.class).list().stream()
                .collect(Collectors.groupingBy(ItemRow::orderId, Collectors.mapping(
                        row -> new Item(row.name(), row.quantity(), row.unitPrice(), options(row.options()), row.notes(),
                                row.lineTotal()), Collectors.toList())));
        List<OrderView> orders = new ArrayList<>();
        for (Row row : rows) {
            orders.add(new OrderView(row.id(), row.number(), row.status(), row.createdAt(), row.updatedAt(),
                    row.displayName(), row.waId(), row.fulfillment(), row.address(), row.neighborhood(),
                    row.deliveryFee(), row.paymentMethod(), row.changeFor(), row.subtotal(), row.total(),
                    items.getOrDefault(row.id(), List.of())));
        }
        return orders;
    }

    private List<String> options(String json) {
        List<String> options = new ArrayList<>();
        mapper.readTree(json).forEach(option -> options.add(option.asString()));
        return options;
    }
}
