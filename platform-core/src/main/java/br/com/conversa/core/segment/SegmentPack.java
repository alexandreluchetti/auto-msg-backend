package br.com.conversa.core.segment;

import br.com.conversa.core.tool.Tool;

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
}
