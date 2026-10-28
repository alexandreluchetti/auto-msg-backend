package br.com.conversa.core.tenant;

/**
 * Read port for a tenant's effective configuration: the Segment Pack defaults merged with the
 * tenant's own settings, already valid against the pack's schema.
 */
@FunctionalInterface
public interface TenantSettings {

    /** JSON object with the effective configuration. */
    String effectiveConfigJson(TenantId tenant);
}
