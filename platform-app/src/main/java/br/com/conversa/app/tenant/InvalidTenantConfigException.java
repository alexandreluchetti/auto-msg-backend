package br.com.conversa.app.tenant;

import java.util.List;

/** A tenant configuration that does not satisfy its Segment Pack's schema. */
public class InvalidTenantConfigException extends RuntimeException {

    private final List<String> errors;

    public InvalidTenantConfigException(List<String> errors) {
        super("Invalid tenant configuration: " + String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> errors() {
        return errors;
    }
}
