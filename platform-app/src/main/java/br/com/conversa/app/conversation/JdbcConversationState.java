package br.com.conversa.app.conversation;

import br.com.conversa.app.tenant.TenantTransactions;
import br.com.conversa.core.conversation.ConversationState;
import br.com.conversa.core.tenant.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * The pack's state in {@code conversation.context}. Writing it supersedes the pending order
 * summary, so an old "Confirmar pedido" button cannot confirm a cart that changed after it.
 */
@Repository
class JdbcConversationState implements ConversationState {

    private final JdbcClient jdbc;
    private final TenantTransactions tenants;

    JdbcConversationState(JdbcClient jdbc, TenantTransactions tenants) {
        this.jdbc = jdbc;
        this.tenants = tenants;
    }

    @Override
    public String read(TenantId tenant, String conversationId) {
        return tenants.inTenant(tenant, () -> jdbc.sql("SELECT context::text FROM conversation WHERE id = :id")
                .param("id", UUID.fromString(conversationId))
                .query(String.class).optional().orElse("{}"));
    }

    @Override
    public void write(TenantId tenant, String conversationId, String stateJson) {
        UUID conversation = UUID.fromString(conversationId);
        tenants.runInTenant(tenant, () -> {
            jdbc.sql("UPDATE conversation SET context = CAST(:context AS jsonb), updated_at = now() WHERE id = :id")
                    .param("context", stateJson)
                    .param("id", conversation)
                    .update();
            jdbc.sql("UPDATE checkout SET status = 'SUPERSEDED' WHERE conversation_id = :id AND status = 'PENDING'")
                    .param("id", conversation)
                    .update();
        });
    }
}
