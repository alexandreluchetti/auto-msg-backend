package br.com.conversa.app.api;

import br.com.conversa.core.tenant.TenantId;

import java.util.UUID;

/**
 * Something the panel shows live (SSE), published inside the transaction that caused it and sent
 * only after it commits.
 *
 * @param type {@code order.created}, {@code order.updated} or {@code conversation.handoff} /
 *             {@code conversation.resumed}
 */
public record PanelEvent(TenantId tenant, String type, UUID id) {

    public static PanelEvent orderCreated(TenantId tenant, UUID order) {
        return new PanelEvent(tenant, "order.created", order);
    }

    public static PanelEvent orderUpdated(TenantId tenant, UUID order) {
        return new PanelEvent(tenant, "order.updated", order);
    }

    public static PanelEvent handoff(TenantId tenant, UUID conversation) {
        return new PanelEvent(tenant, "conversation.handoff", conversation);
    }

    public static PanelEvent resumed(TenantId tenant, UUID conversation) {
        return new PanelEvent(tenant, "conversation.resumed", conversation);
    }
}
