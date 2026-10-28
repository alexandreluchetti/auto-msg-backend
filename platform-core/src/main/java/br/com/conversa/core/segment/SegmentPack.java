package br.com.conversa.core.segment;

import br.com.conversa.core.tool.Tool;

import java.util.List;

/**
 * SPI de um segmento de negócio (restaurante, clínica, salão...).
 *
 * <p>Um pack fornece ferramentas e, em {@code classpath:segments/<id>/}, fluxos, prompts,
 * schema e defaults de configuração. Cada tenant escolhe um pack e uma versão e o customiza
 * por configuração. Ver ADR-003.
 */
public interface SegmentPack {

    /** Identificador estável, ex.: {@code "restaurant"}. */
    String id();

    /** Versão do pack; tenants fixam a versão que usam. */
    int version();

    /** Ferramentas que o LLM e os fluxos podem chamar para tenants deste segmento. */
    List<Tool> tools();

    default String resourceBasePath() {
        return "segments/" + id() + "/";
    }
}
