package br.com.conversa.core.segment;

import br.com.conversa.core.tool.Tool;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * SPI of a business segment (restaurant, clinic, salon...).
 *
 * <p>A pack provides tools and, in {@code classpath:segments/<id>/}, flows, prompts,
 * configuration schema and defaults. Each tenant picks a pack and a version and customizes it
 * through configuration. See ADR-003.
 */
public interface SegmentPack {

    /** Stable identifier, e.g. {@code "restaurant"}. */
    String id();

    /** Pack version; tenants pin the version they use. */
    int version();

    /** Tools the LLM and the flows may call for tenants of this segment. */
    List<Tool> tools();

    default String resourceBasePath() {
        return "segments/" + id() + "/";
    }

    /** JSON Schema (draft 2020-12) of the tenant configuration, from {@code config-schema.json}. */
    default String configSchemaJson() {
        return resource("config-schema.json");
    }

    /**
     * Pack defaults, from {@code defaults.json}. A tenant's configuration is merged over them and
     * the result must satisfy {@link #configSchemaJson()}.
     */
    default String defaultConfigJson() {
        return resource("defaults.json");
    }

    /**
     * System prompt of the assistant for a tenant, from {@code prompts/system.md}. It must depend
     * only on the configuration (never on the time or the conversation), so the provider can cache
     * it across conversations.
     *
     * @param effectiveConfigJson the tenant's effective configuration
     */
    default String systemPrompt(String effectiveConfigJson) {
        return resource("prompts/system.md");
    }

    /** Reads a text resource of this pack. */
    default String resource(String name) {
        String path = resourceBasePath() + name;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Segment pack resource not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read segment pack resource " + path, e);
        }
    }
}
