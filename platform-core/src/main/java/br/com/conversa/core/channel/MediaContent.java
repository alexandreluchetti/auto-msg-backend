package br.com.conversa.core.channel;

import java.util.Objects;

/** A file a customer sent (audio, image), downloaded from the channel for this turn only. */
public record MediaContent(byte[] bytes, String mimeType) {

    public MediaContent {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(mimeType, "mimeType");
    }
}
