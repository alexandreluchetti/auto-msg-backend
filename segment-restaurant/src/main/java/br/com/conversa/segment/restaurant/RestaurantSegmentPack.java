package br.com.conversa.segment.restaurant;

import br.com.conversa.core.segment.SegmentPack;
import br.com.conversa.core.tool.Tool;

import java.util.List;

/**
 * Segment Pack de restaurante. Ferramentas (cardápio, taxa de entrega, carrinho) entram a
 * partir do M4; ver docs/ROADMAP.md.
 */
public final class RestaurantSegmentPack implements SegmentPack {

    public static final String ID = "restaurant";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public List<Tool> tools() {
        return List.of();
    }
}
