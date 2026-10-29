package br.com.conversa.app.conversation;

import br.com.conversa.app.ops.OperationsMetrics;
import br.com.conversa.app.tenant.TenantTransactions;
import br.com.conversa.core.channel.ChannelGateway;
import br.com.conversa.core.channel.MediaContent;
import br.com.conversa.core.llm.LlmContent;
import br.com.conversa.core.speech.SpeechToText;
import br.com.conversa.core.tenant.TenantId;
import br.com.conversa.speech.openai.OpenAiSpeechProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Makes the customer's media readable before an answer (ADR-011 step H6, ADR-012): voice notes are
 * downloaded and transcribed once, the transcription replacing the "[áudio]" marker in the history
 * (the audio is not kept) and its cost recorded per tenant; images of the unanswered messages are
 * downloaded for this answer only. Anything that fails keeps a marker the prompt turns into a
 * natural request to write. Runs outside transactions: downloads and transcription are slow.
 */
@Component
class MediaInbox {

    private static final Logger log = LoggerFactory.getLogger(MediaInbox.class);
    static final String NOT_TRANSCRIBED = "[áudio não transcrito]";
    /** Image types the model reads. */
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");
    /** Opus voice notes from WhatsApp average about 2 KB per second; other formats are larger. */
    private static final double VOICE_NOTE_BYTES_PER_SECOND = 2_000;
    private static final double OTHER_AUDIO_BYTES_PER_SECOND = 16_000;

    private final JdbcClient jdbc;
    private final TenantTransactions tenants;
    private final ChannelGateway gateway;
    private final SpeechToText speech;
    private final OpenAiSpeechProperties speechProperties;
    private final MediaProperties properties;
    private final OperationsMetrics metrics;
    private final Clock clock;

    MediaInbox(JdbcClient jdbc, TenantTransactions tenants, ChannelGateway gateway, SpeechToText speech,
               OpenAiSpeechProperties speechProperties, MediaProperties properties, OperationsMetrics metrics,
               Clock clock) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.gateway = gateway;
        this.speech = speech;
        this.speechProperties = speechProperties;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    private record Media(UUID id, String wamid, String mediaId, String mediaMime) {
    }

    /**
     * Transcribes pending voice notes of the conversation and downloads the images the customer sent
     * since the bot's last answer.
     *
     * @return images by the wamid of their message, for the next model request only
     */
    Map<String, LlmContent.Image> prepare(TenantId tenant, UUID conversation, String phoneNumberId) {
        List<Media> audios = tenants.inTenant(tenant, () -> jdbc.sql("""
                        SELECT id, wamid, media_id, media_mime FROM message
                        WHERE conversation_id = :conversation AND media_status = 'PENDING'
                        ORDER BY created_at
                        """)
                .param("conversation", conversation).query(Media.class).list());
        for (Media audio : audios) {
            transcribe(tenant, conversation, phoneNumberId, audio);
        }
        List<Media> images = tenants.inTenant(tenant, () -> jdbc.sql("""
                        SELECT m.id, m.wamid, m.media_id, m.media_mime FROM message m
                        WHERE m.conversation_id = :conversation AND m.type = 'image' AND m.media_id IS NOT NULL
                          AND NOT EXISTS (SELECT 1 FROM message o WHERE o.conversation_id = m.conversation_id
                                            AND o.direction = 'OUT' AND o.created_at > m.created_at)
                        ORDER BY m.created_at
                        """)
                .param("conversation", conversation).query(Media.class).list());
        Map<String, LlmContent.Image> read = new HashMap<>();
        for (Media image : images) {
            String type = mimeType(image.mediaMime());
            if (!IMAGE_TYPES.contains(type)) {
                continue;
            }
            gateway.download(phoneNumberId, image.mediaId(), properties.maxImageBytes())
                    .ifPresentOrElse(content -> read.put(image.wamid(), new LlmContent.Image(type, content.bytes())),
                            () -> log.info("Image of conversation {} could not be downloaded", conversation));
        }
        return read;
    }

    private void transcribe(TenantId tenant, UUID conversation, String phoneNumberId, Media audio) {
        Optional<SpeechToText.Transcription> transcription = Optional.empty();
        if (speech.configured()) {
            Optional<MediaContent> content = gateway.download(phoneNumberId, audio.mediaId(), properties.maxAudioBytes());
            if (content.isPresent() && estimatedSeconds(content.get()) <= properties.maxAudio().toSeconds()) {
                Instant started = clock.instant();
                transcription = speech.transcribe(content.get().bytes(), content.get().mimeType());
                transcription.ifPresent(result -> recordCost(tenant, conversation, result,
                        Duration.between(started, clock.instant())));
            }
        }
        String text = transcription.map(result -> "[áudio] " + result.text()).orElse(NOT_TRANSCRIBED);
        String status = transcription.isPresent() ? "DONE" : "FAILED";
        tenants.runInTenant(tenant, () -> jdbc.sql("""
                        UPDATE message SET text = :text, media_status = :status
                        WHERE id = :id AND media_status = 'PENDING'
                        """)
                .param("text", text).param("status", status).param("id", audio.id()).update());
        log.info("Voice note of conversation {}: {}", conversation, status);
    }

    private void recordCost(TenantId tenant, UUID conversation, SpeechToText.Transcription result, Duration duration) {
        BigDecimal cost = speechProperties.pricePerMinute()
                .multiply(BigDecimal.valueOf(result.seconds() / 60.0), MathContext.DECIMAL64);
        tenants.runInTenant(tenant, () -> jdbc.sql("""
                        INSERT INTO llm_call (tenant_id, conversation_id, requested_model, model, effort, stop_reason,
                                              input_tokens, output_tokens, cache_read_tokens, cache_write_tokens,
                                              cost_usd, duration_ms)
                        VALUES (:tenant, :conversation, :model, :model, 'none', 'TRANSCRIPTION', 0, 0, 0, 0, :cost,
                                :duration)
                        """)
                .param("tenant", tenant.value()).param("conversation", conversation).param("model", result.model())
                .param("cost", cost).param("duration", duration.toMillis())
                .update());
        metrics.llmCall(tenant, result.model(), cost, 0, 0, 0, 0);
    }

    private static double estimatedSeconds(MediaContent content) {
        double rate = mimeType(content.mimeType()).equals("audio/ogg")
                ? VOICE_NOTE_BYTES_PER_SECOND : OTHER_AUDIO_BYTES_PER_SECOND;
        return content.bytes().length / rate;
    }

    private static String mimeType(String value) {
        return value == null ? "" : value.split(";")[0].strip().toLowerCase(Locale.ROOT);
    }
}
