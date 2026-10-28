package br.com.conversa.app.tenant;

import br.com.conversa.core.segment.SegmentPack;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Segment Packs deployed in this build, one version each. A tenant pinned to another version
 * of a pack cannot be served until that version is deployed.
 */
@Component
public class SegmentPackRegistry {

    private final Map<String, SegmentPack> packs;

    public SegmentPackRegistry(List<SegmentPack> packs) {
        this.packs = packs.stream().collect(Collectors.toUnmodifiableMap(SegmentPack::id, Function.identity()));
    }

    public SegmentPack require(String segmentId, int version) {
        SegmentPack pack = packs.get(segmentId);
        if (pack == null) {
            throw new UnknownSegmentException("Unknown segment: " + segmentId);
        }
        if (pack.version() != version) {
            throw new UnknownSegmentException("Segment " + segmentId + " v" + version
                    + " is not deployed (deployed: v" + pack.version() + ")");
        }
        return pack;
    }

    /** The deployed version, used when a new tenant is created. */
    public SegmentPack latest(String segmentId) {
        SegmentPack pack = packs.get(segmentId);
        if (pack == null) {
            throw new UnknownSegmentException("Unknown segment: " + segmentId);
        }
        return pack;
    }

    public static class UnknownSegmentException extends RuntimeException {
        UnknownSegmentException(String message) {
            super(message);
        }
    }
}
