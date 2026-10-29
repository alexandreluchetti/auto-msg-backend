package br.com.conversa.speech.openai;

import br.com.conversa.core.speech.SpeechToText;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiSpeechToTextTest {

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String answer = "{\"text\": \" Quero uma pizza grande \", \"usage\": {\"type\": \"duration\", \"seconds\": 7}}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/audio/transcriptions", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private OpenAiSpeechToText client(String key) {
        return new OpenAiSpeechToText(new OpenAiSpeechProperties(key, "gpt-4o-mini-transcribe", "pt",
                "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(5), new BigDecimal("0.003")));
    }

    @Test
    void transcribesAVoiceNote() {
        var result = client("sk-test").transcribe(new byte[]{1, 2, 3}, "audio/ogg; codecs=opus");

        assertThat(result).contains(new SpeechToText.Transcription("Quero uma pizza grande", "gpt-4o-mini-transcribe", 7));
        assertThat(authorization.get()).isEqualTo("Bearer sk-test");
        assertThat(body.get()).contains("name=\"model\"\r\n\r\ngpt-4o-mini-transcribe", "name=\"language\"\r\n\r\npt",
                "filename=\"audio.ogg\"", "Content-Type: audio/ogg; codecs=opus");
    }

    @Test
    void estimatesTheLengthWhenTheApiDoesNotSayIt() {
        answer = "{\"text\": \"oi\"}";

        var result = client("sk-test").transcribe(new byte[4_000], "audio/ogg");

        assertThat(result).get().extracting(SpeechToText.Transcription::seconds).isEqualTo(2.0);
    }

    @Test
    void failuresAndSilenceBecomeEmpty() {
        answer = "{\"text\": \"  \"}";
        assertThat(client("sk-test").transcribe(new byte[]{1}, "audio/ogg")).isEmpty();

        status = 500;
        answer = "{\"error\": {}}";
        assertThat(client("sk-test").transcribe(new byte[]{1}, "audio/ogg")).isEmpty();
    }

    @Test
    void withoutAKeyNothingIsCalled() {
        OpenAiSpeechToText client = client("");

        assertThat(client.configured()).isFalse();
        assertThat(client.transcribe(new byte[]{1}, "audio/ogg")).isEmpty();
        assertThat(body.get()).isNull();
    }

    @Test
    void picksTheFileExtensionFromTheMimeType() {
        assertThat(OpenAiSpeechToText.extension("audio/mpeg")).isEqualTo(".mp3");
        assertThat(OpenAiSpeechToText.extension("audio/mp4")).isEqualTo(".m4a");
        assertThat(OpenAiSpeechToText.extension("audio/ogg; codecs=opus")).isEqualTo(".ogg");
    }
}
