package br.com.conversa.core.tool;

import br.com.conversa.core.tenant.TenantId;

/**
 * Chamada de ferramenta já validada contra o schema, sempre no contexto de um tenant.
 */
public record ToolInvocation(TenantId tenantId, String conversationId, String inputJson) {
}
