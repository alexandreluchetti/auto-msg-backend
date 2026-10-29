package br.com.conversa.speech.openai;

import br.com.conversa.core.speech.SpeechToText;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link SpeechToText} with the OpenAI transcriptions endpoint (multipart upload). Failures are
 * logged without content and become an empty result: the assistant then asks the customer to write.
 */
public final class OpenAiSpeechToText implements SpeechToText {

    private static final System.Logger log = System.getLogger(OpenAiSpeechToText.class.getName());
    /** Opus voice notes from WhatsApp average about 2 KB per second. */
    static final double BYTES_PER_SECOND = 2_000;

    private final OpenAiSpeechProperties properties;
    private final HttpClient http;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public OpenAiSpeechToText(OpenAiSpeechProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder().connectTimeout(properties.timeout()).build();
    }

    @Override
    public boolean configured() {
        return properties.apiKey() != null && !properties.apiKey().isBlank();
    }

    @Override
    public Optional<Transcription> transcribe(byte[] audio, String mimeType) {
        if (!configured()) {
            return Optional.empty();
        }
        String boundary = "conversa-" + UUID.randomUUID();
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.baseUrl() + "/v1/audio/transcriptions"))
                .timeout(properties.timeout())
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, audio, mimeType)))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.log(System.Logger.Level.WARNING, "Transcription failed with HTTP {0}", response.statusCode());
                return Optional.empty();
            }
            JsonNode body = mapper.readTree(response.body());
            String text = body.path("text").asString("").strip();
            if (text.isEmpty()) {
                return Optional.empty();
            }
            double seconds = body.path("usage").path("seconds").asDouble(audio.length / BYTES_PER_SECOND);
            return Optional.of(new Transcription(text, properties.model(), seconds));
        } catch (IOException | RuntimeException e) {
            log.log(System.Logger.Level.WARNING, "Transcription failed: {0}", e.getClass().getSimpleName());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private byte[] multipart(String boundary, byte[] audio, String mimeType) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        field(body, boundary, "model", properties.model());
        field(body, boundary, "language", properties.language());
        field(body, boundary, "response_format", "json");
        String type = mimeType == null || mimeType.isBlank() ? "audio/ogg" : mimeType;
        write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio"
                + extension(type) + "\"\r\nContent-Type: " + type + "\r\n\r\n");
        body.writeBytes(audio);
        write(body, "\r\n--" + boundary + "--\r\n");
        return body.toByteArray();
    }

    private static void field(ByteArrayOutputStream body, String boundary, String name, String value) {
        write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value
                + "\r\n");
    }

    private static void write(ByteArrayOutputStream body, String text) {
        body.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    /** The API picks the decoder by file name. */
    static String extension(String mimeType) {
        String type = mimeType.split(";")[0].strip();
        return switch (type) {
            case "audio/mpeg" -> ".mp3";
            case "audio/mp4", "audio/aac" -> ".m4a";
            case "audio/wav", "audio/x-wav" -> ".wav";
            case "audio/webm" -> ".webm";
            default -> ".ogg";
        };
    }
}
