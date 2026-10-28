package br.com.conversa.core.llm;

/** The provider could not answer. {@code retryable} for rate limits, overload and network errors. */
public class LlmException extends RuntimeException {

    private final boolean retryable;

    public LlmException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
