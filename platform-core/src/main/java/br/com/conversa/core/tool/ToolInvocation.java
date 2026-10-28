package br.com.conversa.core.tool;

import br.com.conversa.core.tenant.TenantId;

/**
 * Tool call already validated against the schema, always in the context of a tenant.
 */
public record ToolInvocation(TenantId tenantId, String conversationId, String inputJson) {
}
