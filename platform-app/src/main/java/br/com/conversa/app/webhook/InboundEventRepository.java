package br.com.conversa.app.webhook;

import br.com.conversa.channel.whatsapp.webhook.WebhookEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Grava eventos do webhook na fila {@code inbound_event}. Duplicatas são ignoradas pelo
 * {@code event_key} único, porque a Meta reentrega notificações.
 */
@Repository
public class InboundEventRepository {

    private static final String INSERT = """
            INSERT INTO inbound_event (event_key, event_type, field, waba_id, phone_number_id, wa_id, payload)
            VALUES (:eventKey, :eventType, :field, :wabaId, :phoneNumberId, :waId, CAST(:payload AS jsonb))
            ON CONFLICT (event_key) DO NOTHING
            """;

    private final JdbcClient jdbc;

    public InboundEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return quantos eventos eram novos (os demais já estavam gravados)
     */
    @Transactional
    public int saveAll(List<WebhookEvent> events) {
        int inserted = 0;
        for (WebhookEvent event : events) {
            inserted += jdbc.sql(INSERT)
                    .param("eventKey", event.eventKey())
                    .param("eventType", event.type().name())
                    .param("field", event.field())
                    .param("wabaId", event.wabaId())
                    .param("phoneNumberId", event.phoneNumberId())
                    .param("waId", event.waId())
                    .param("payload", event.payloadJson())
                    .update();
        }
        return inserted;
    }
}
