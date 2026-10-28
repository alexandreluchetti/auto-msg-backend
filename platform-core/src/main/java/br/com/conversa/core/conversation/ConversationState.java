package br.com.conversa.core.conversation;

import br.com.conversa.core.tenant.TenantId;

/**
 * Port to the Segment Pack's own state of a conversation (e.g. the restaurant's cart), a JSON
 * object kept with the conversation. Writing it invalidates any order summary shown before: the
 * customer can only confirm what matches the current state.
 */
public interface ConversationState {

    /** The pack's state, {@code {}} when nothing was written yet. */
    String read(TenantId tenant, String conversationId);

    void write(TenantId tenant, String conversationId, String stateJson);
}
