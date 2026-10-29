package br.com.conversa.core.speech;

import java.util.Optional;

/**
 * Port to a speech-to-text provider (ADR-012): voice messages become text before the assistant
 * answers them.
 */
public interface SpeechToText {

    /** False when the adapter has no credentials; audio then stays a marker the assistant asks about. */
    boolean configured();

    /**
     * @param audio    the voice message as sent by the channel
     * @param mimeType e.g. {@code audio/ogg; codecs=opus}
     * @return the transcription, or empty when the provider could not understand it or failed
     */
    Optional<Transcription> transcribe(byte[] audio, String mimeType);

    /** @param seconds length of the audio as billed, estimated when the provider does not say */
    record Transcription(String text, String model, double seconds) {
    }
}
