package br.com.conversa.app.order;

import br.com.conversa.app.api.PanelEvent;
import br.com.conversa.app.outbound.OutboundMessageRepository;
import br.com.conversa.app.outbound.OutboundRequest;
import br.com.conversa.core.order.OrderDraft;
import br.com.conversa.core.order.OrderLine;
import br.com.conversa.core.tenant.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Order summaries and their confirmation (D-006). A summary shown to the customer is a pending
 * {@code checkout}; the customer's tap on its button, and nothing else, creates the order, exactly as
 * summarized. A second tap, or a tap on an outdated summary, creates nothing. Must run inside the
 * tenant's transaction.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    public static final String CONFIRM_PREFIX = "confirm:";
    public static final String EDIT_PREFIX = "edit:";

    static final String OUTDATED_TEXT = "Esse resumo não vale mais, porque o pedido mudou depois dele. "
            + "Confira o resumo mais recente e toque em *Confirmar pedido* nele.";

    public enum Result { CREATED, ALREADY_CONFIRMED, OUTDATED }

    /** What happened to a tap, and what to tell the customer. */
    public record Confirmation(Result result, Integer number, String customerText) {
    }

    private final JdbcClient jdbc;
    private final OutboundMessageRepository outbound;
    private final OrderProperties properties;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper;

    OrderService(JdbcClient jdbc, OutboundMessageRepository outbound, OrderProperties properties,
                 ApplicationEventPublisher events, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.outbound = outbound;
        this.properties = properties;
        this.events = events;
        this.mapper = mapper;
    }

    /** Stores a summary about to be shown; earlier pending summaries no longer confirm. */
    public UUID openCheckout(TenantId tenant, UUID conversation, OrderDraft draft) {
        jdbc.sql("UPDATE checkout SET status = 'SUPERSEDED' WHERE conversation_id = :id AND status = 'PENDING'")
                .param("id", conversation)
                .update();
        return jdbc.sql("""
                        INSERT INTO checkout (tenant_id, conversation_id, draft)
                        VALUES (:tenant, :conversation, CAST(:draft AS jsonb))
                        RETURNING id
                        """)
                .param("tenant", tenant.value())
                .param("conversation", conversation)
                .param("draft", mapper.writeValueAsString(draft))
                .query(UUID.class).single();
    }

    /**
     * The customer tapped "Confirmar pedido" on checkout {@code checkoutId}.
     *
     * @param tapWamid      wamid of the tap, stored as the order's confirmation
     * @param customerLabel how staff alerts name the customer
     */
    public Confirmation confirm(TenantId tenant, UUID conversation, String checkoutId, String tapWamid,
                                String phoneNumberId, String customerLabel, JsonNode config) {
        UUID id;
        try {
            id = UUID.fromString(checkoutId);
        } catch (IllegalArgumentException e) {
            return new Confirmation(Result.OUTDATED, null, OUTDATED_TEXT);
        }
        record Pending(String status, String draft) {
        }
        Optional<Pending> checkout = jdbc.sql("""
                        SELECT status, draft::text AS draft FROM checkout
                        WHERE id = :id AND conversation_id = :conversation
                        FOR UPDATE
                        """)
                .param("id", id)
                .param("conversation", conversation)
                .query(Pending.class).optional();
        if (checkout.isPresent() && "CONFIRMED".equals(checkout.get().status())) {
            Integer number = jdbc.sql("SELECT number FROM customer_order WHERE checkout_id = :id")
                    .param("id", id).query(Integer.class).single();
            return new Confirmation(Result.ALREADY_CONFIRMED, number,
                    "Seu pedido *#" + number + "* já está confirmado e a equipe já recebeu.");
        }
        if (checkout.isEmpty() || !"PENDING".equals(checkout.get().status())) {
            return new Confirmation(Result.OUTDATED, null, OUTDATED_TEXT);
        }

        OrderDraft draft = mapper.readValue(checkout.get().draft(), OrderDraft.class);
        int number = nextNumber(tenant);
        UUID order = jdbc.sql("""
                        INSERT INTO customer_order (tenant_id, number, conversation_id, contact_id, checkout_id,
                                                    confirmation_wamid, fulfillment, address, neighborhood, delivery_fee,
                                                    payment_method, change_for, subtotal, total)
                        SELECT :tenant, :number, c.id, c.contact_id, :checkout, :wamid, :fulfillment, :address,
                               :neighborhood, :fee, :payment, :changeFor, :subtotal, :total
                        FROM conversation c WHERE c.id = :conversation
                        RETURNING id
                        """)
                .param("tenant", tenant.value())
                .param("number", number)
                .param("checkout", id)
                .param("wamid", tapWamid)
                .param("fulfillment", draft.fulfillment())
                .param("address", draft.address())
                .param("neighborhood", draft.neighborhood())
                .param("fee", draft.deliveryFee())
                .param("payment", draft.paymentMethod())
                .param("changeFor", draft.changeFor())
                .param("subtotal", draft.subtotal())
                .param("total", draft.total())
                .param("conversation", conversation)
                .query(UUID.class).single();
        List<OrderLine> lines = draft.lines();
        for (int position = 0; position < lines.size(); position++) {
            OrderLine line = lines.get(position);
            jdbc.sql("""
                            INSERT INTO order_item (tenant_id, order_id, position, catalog_item_id, code, name, quantity,
                                                    unit_price, options, notes, line_total)
                            VALUES (:tenant, :order, :position, :item, :code, :name, :quantity, :unitPrice,
                                    CAST(:options AS jsonb), :notes, :lineTotal)
                            """)
                    .param("tenant", tenant.value())
                    .param("order", order)
                    .param("position", position)
                    .param("item", line.catalogItemId())
                    .param("code", line.code())
                    .param("name", line.name())
                    .param("quantity", line.quantity())
                    .param("unitPrice", line.unitPrice())
                    .param("options", mapper.writeValueAsString(line.options()))
                    .param("notes", line.notes())
                    .param("lineTotal", line.lineTotal())
                    .update();
        }
        jdbc.sql("UPDATE checkout SET status = 'CONFIRMED' WHERE id = :id").param("id", id).update();
        jdbc.sql("UPDATE conversation SET context = '{}', updated_at = now() WHERE id = :id")
                .param("id", conversation).update();
        alertStaff(tenant, phoneNumberId, number, customerLabel, draft, config);
        events.publishEvent(PanelEvent.orderCreated(tenant, order));
        log.info("Order #{} created for tenant {}", number, tenant);
        return new Confirmation(Result.CREATED, number, confirmedText(number, draft));
    }

    static String confirmedText(int number, OrderDraft draft) {
        String fulfillment = "DELIVERY".equals(draft.fulfillment())
                ? "entrega em " + draft.address() + " - " + draft.neighborhood()
                : "retirada no restaurante";
        return "Pedido *#" + number + "* confirmado!\nTotal: " + Money.format(draft.total()) + ", " + fulfillment
                + ".\nA equipe do restaurante já recebeu e vai preparar. Qualquer novidade, avisamos por aqui.";
    }

    private int nextNumber(TenantId tenant) {
        return jdbc.sql("""
                        INSERT INTO order_sequence (tenant_id, last_number) VALUES (:tenant, 1)
                        ON CONFLICT (tenant_id) DO UPDATE SET last_number = order_sequence.last_number + 1
                        RETURNING last_number
                        """)
                .param("tenant", tenant.value())
                .query(Integer.class).single();
    }

    private void alertStaff(TenantId tenant, String phoneNumberId, int number, String customerLabel, OrderDraft draft,
                            JsonNode config) {
        if (properties.staffAlertTemplate() == null || properties.staffAlertTemplate().isBlank()) {
            log.warn("No new-order template configured (conversa.orders.staff-alert-template); staff of tenant {} "
                    + "see order #{} only in the app and the panel", tenant, number);
            return;
        }
        String fulfillment = "DELIVERY".equals(draft.fulfillment()) ? "Entrega - " + draft.neighborhood() : "Retirada";
        for (JsonNode staff : config.path("staffNumbers")) {
            outbound.enqueue("order:" + tenant + ":" + number + ":" + staff.asString(), tenant,
                    OutboundRequest.template(phoneNumberId, staff.asString(), properties.staffAlertTemplate(),
                            properties.staffAlertLanguage(),
                            List.of("#" + number, customerLabel, fulfillment, Money.format(draft.total())), mapper));
        }
    }
}
