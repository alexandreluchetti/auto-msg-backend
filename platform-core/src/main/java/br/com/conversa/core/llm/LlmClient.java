package br.com.conversa.core.llm;

/**
 * Port to a language model (ADR-005). One call is one model request; the tool loop lives in the
 * conversation engine, which controls limits, validation and cost recording.
 */
public interface LlmClient {

    /** False when the adapter has no credentials; the engine then does not answer. */
    boolean configured();

    /**
     * @throws LlmException when the provider cannot answer (network, rate limit, invalid request)
     */
    LlmResponse complete(LlmRequest request);
}
