package br.com.conversa.app.memory;

import br.com.conversa.app.conversation.AssistantEngine;
import br.com.conversa.app.conversation.AssistantProperties;
import br.com.conversa.app.tenant.TenantTransactions;
import br.com.conversa.core.llm.LlmClient;
import br.com.conversa.core.llm.LlmException;
import br.com.conversa.core.llm.LlmMessage;
import br.com.conversa.core.llm.LlmRequest;
import br.com.conversa.core.llm.LlmResponse;
import br.com.conversa.core.tenant.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Rolling summary of long conversations (ADR-011, step H5): the assistant sees the latest
 * {@code historyMessages} messages and, when there are older ones, a summary of them. The summary
 * is extended with a cheap model once at least {@link #BATCH} older messages are not covered yet,
 * so most turns cost nothing extra. A failure keeps the previous summary.
 */
@Component
public class ConversationSummarizer {

    private static final Logger log = LoggerFactory.getLogger(ConversationSummarizer.class);

    static final int BATCH = 10;
    private static final OffsetDateTime EPOCH = OffsetDateTime.parse("1970-01-01T00:00:00Z");
    static final String PROMPT = """
            Você resume conversas de WhatsApp entre um cliente e um restaurante, para que o atendimento \
            continue sem perder o contexto. Escreva em português, em até 8 linhas curtas, só com o que foi \
            dito: o que o cliente quer ou pediu, preferências, endereço ou forma de pagamento mencionados, \
            problemas e combinados. Não invente nada nem acrescente preços que não estejam na conversa.""";

    private final JdbcClient jdbc;
    private final TenantTransactions tenants;
    private final LlmClient llm;
    private final AssistantEngine.CallLog calls;
    private final AssistantProperties properties;
    private final Clock clock;

    ConversationSummarizer(JdbcClient jdbc, TenantTransactions tenants, LlmClient llm, AssistantEngine.CallLog calls,
                           AssistantProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.llm = llm;
        this.calls = calls;
        this.properties = properties;
        this.clock = clock;
    }

    /** The summary of what came before the latest messages, extended first if it is due. */
    public String summaryFor(TenantId tenant, UUID conversation) {
        record Summary(String summary, OffsetDateTime coversUntil) {
        }
        record Older(OffsetDateTime windowStart, List<String> uncovered, OffsetDateTime last) {
        }
        Optional<Summary> current = tenants.inTenant(tenant, () -> jdbc.sql("""
                        SELECT summary, covers_until FROM conversation_summary WHERE conversation_id = :id
                        """)
                .param("id", conversation).query(Summary.class).optional());
        Older older = tenants.inTenant(tenant, () -> {
            Optional<OffsetDateTime> windowStart = jdbc.sql("""
                            SELECT created_at FROM message WHERE conversation_id = :id
                            ORDER BY created_at DESC OFFSET :offset LIMIT 1
                            """)
                    .param("id", conversation).param("offset", properties.historyMessages() - 1)
                    .query(OffsetDateTime.class).optional();
            if (windowStart.isEmpty()) {
                return null;
            }
            OffsetDateTime since = current.map(Summary::coversUntil).orElse(EPOCH);
            record Line(String direction, String author, String text, OffsetDateTime createdAt) {
            }
            List<Line> lines = jdbc.sql("""
                            SELECT direction, author, text, created_at FROM message
                            WHERE conversation_id = :id AND created_at < :windowStart AND created_at > :since
                            ORDER BY created_at
                            """)
                    .param("id", conversation).param("windowStart", windowStart.get()).param("since", since)
                    .query(Line.class).list();
            return new Older(windowStart.get(), lines.stream().map(line -> ("IN".equals(line.direction()) ? "Cliente"
                    : "STAFF".equals(line.author()) ? "Equipe" : "Restaurante") + ": " + line.text()).toList(),
                    lines.isEmpty() ? null : lines.getLast().createdAt());
        });
        String summary = current.map(Summary::summary).orElse("");
        if (older == null || older.uncovered().size() < BATCH) {
            return summary;
        }
        try {
            String extended = summarize(tenant, conversation, summary, older.uncovered());
            tenants.runInTenant(tenant, () -> jdbc.sql("""
                            INSERT INTO conversation_summary (conversation_id, tenant_id, summary, covers_until)
                            VALUES (:id, :tenant, :summary, :until)
                            ON CONFLICT (conversation_id)
                                DO UPDATE SET summary = excluded.summary, covers_until = excluded.covers_until,
                                              updated_at = now()
                            """)
                    .param("id", conversation).param("tenant", tenant.value()).param("summary", extended)
                    .param("until", older.last()).update());
            return extended;
        } catch (LlmException | IllegalStateException e) {
            log.warn("Conversation {}: summary not extended ({}); keeping the previous one", conversation,
                    e.getMessage());
            return summary;
        }
    }

    private String summarize(TenantId tenant, UUID conversation, String previous, List<String> lines) {
        String input = (previous.isBlank() ? "" : "Resumo anterior:\n" + previous + "\n\n")
                + "Mensagens a incluir no resumo:\n" + String.join("\n", lines);
        Instant started = clock.instant();
        LlmResponse response = llm.complete(new LlmRequest(properties.summaryModel(), "low", 800, PROMPT, List.of(),
                List.of(LlmMessage.user(input))));
        calls.record(tenant, conversation, properties.summaryModel(), "low", response,
                Duration.between(started, clock.instant()));
        String text = response.text();
        if (text.isBlank()) {
            throw new IllegalStateException("empty summary");
        }
        return text.strip();
    }
}
