package br.com.conversa.core.tenant;

import java.util.Objects;
import java.util.UUID;

/**
 * Identificador de uma empresa (tenant). Todo dado de negócio pertence a exatamente um tenant.
 */
public record TenantId(UUID value) {

    public TenantId {
        Objects.requireNonNull(value, "value");
    }

    public static TenantId of(String value) {
        return new TenantId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
