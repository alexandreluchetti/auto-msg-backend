package br.com.conversa.app.memory;

import br.com.conversa.app.order.Money;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * What the business remembers of a customer between conversations (ADR-011, D-012): the first name
 * they use on WhatsApp, their latest orders and the address used last time, so the assistant can
 * greet them by name and offer "mando no mesmo endereço?". Read from the contact and its orders
 * (no copy to keep in sync), so retention and erasure already clean it; only what the conversation
 * needs goes to the LLM. Must run inside the tenant's transaction.
 */
@Component
public class CustomerMemory {

    static final int LATEST_ORDERS = 3;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM", Locale.of("pt", "BR"));

    private final JdbcClient jdbc;

    CustomerMemory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One sentence block for the assistant's context, empty for a customer it knows nothing about. */
    public String describe(UUID conversation, ZoneId zone) {
        record Contact(UUID id, String displayName) {
        }
        Contact contact = jdbc.sql("""
                        SELECT k.id, k.display_name FROM conversation c JOIN contact k ON k.id = c.contact_id
                        WHERE c.id = :id AND k.erased_at IS NULL
                        """)
                .param("id", conversation).query(Contact.class).optional().orElse(null);
        if (contact == null) {
            return "";
        }
        record Order(UUID id, int number, OffsetDateTime createdAt, String fulfillment, String address,
                     String neighborhood, BigDecimal total) {
        }
        List<Order> orders = jdbc.sql("""
                        SELECT id, number, created_at, fulfillment, address, neighborhood, total FROM customer_order
                        WHERE contact_id = :contact AND status <> 'CANCELLED'
                        ORDER BY created_at DESC LIMIT :limit
                        """)
                .param("contact", contact.id()).param("limit", LATEST_ORDERS).query(Order.class).list();
        List<String> parts = new ArrayList<>();
        String firstName = firstName(contact.displayName());
        if (!firstName.isEmpty()) {
            parts.add("Nome do cliente no WhatsApp: " + firstName + ".");
        }
        if (!orders.isEmpty()) {
            List<String> described = new ArrayList<>();
            for (Order order : orders) {
                String items = String.join(", ", jdbc.sql("""
                                SELECT quantity || 'x ' || name FROM order_item WHERE order_id = :id ORDER BY position
                                """)
                        .param("id", order.id()).query(String.class).list());
                described.add(DAY.format(order.createdAt().atZoneSameInstant(zone)) + ": " + items + " ("
                        + ("DELIVERY".equals(order.fulfillment()) ? "entrega" : "retirada") + ", "
                        + Money.format(order.total()) + ")");
            }
            parts.add("Pedidos anteriores (mais recente primeiro): " + String.join("; ", described) + ".");
            orders.stream().filter(order -> "DELIVERY".equals(order.fulfillment()) && order.address() != null)
                    .findFirst()
                    .ifPresent(order -> parts.add("Endereço da última entrega: " + order.address() + " - "
                            + order.neighborhood() + "."));
        }
        return String.join(" ", parts);
    }

    static String firstName(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "";
        }
        String first = displayName.strip().split("\\s+")[0];
        return first.chars().anyMatch(Character::isLetter) ? first : "";
    }
}
