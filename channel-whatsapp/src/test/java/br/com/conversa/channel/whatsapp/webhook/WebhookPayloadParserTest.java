package br.com.conversa.channel.whatsapp.webhook;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookPayloadParserTest {

    private final WebhookPayloadParser parser = new WebhookPayloadParser();

    @Test
    void parsesIncomingTextMessageWithContact() {
        List<WebhookEvent> events = parser.parse(fixture("text-message.json"));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.eventKey()).isEqualTo("msg:wamid.TEXT0001");
            assertThat(event.type()).isEqualTo(WebhookEventType.MESSAGE);
            assertThat(event.field()).isEqualTo("messages");
            assertThat(event.wabaId()).isEqualTo("100000000000001");
            assertThat(event.phoneNumberId()).isEqualTo("200000000000001");
            assertThat(event.waId()).isEqualTo("5516990000001");
            assertThat(event.payloadJson())
                    .contains("\"item\":{\"from\":\"5516990000001\"")
                    .contains("\"name\":\"Cliente Teste\"")
                    .contains("\"phone_number_id\":\"200000000000001\"");
        });
    }

    @Test
    void splitsBatchIntoOneEventPerMessageAndMatchesEachContact() {
        List<WebhookEvent> events = parser.parse(fixture("batch-messages.json"));

        assertThat(events).extracting(WebhookEvent::eventKey)
                .containsExactly("msg:wamid.BATCH0001", "msg:wamid.BATCH0002");
        assertThat(events.get(1).waId()).isEqualTo("5516990000002");
        assertThat(events.get(1).payloadJson())
                .contains("\"name\":\"Cliente B\"")
                .contains("\"button_reply\":{\"id\":\"checkout.confirm\"");
    }

    @Test
    void parsesStatusKeyedByMessageAndStatusWithPricing() {
        List<WebhookEvent> events = parser.parse(fixture("status-delivered.json"));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.eventKey()).isEqualTo("status:wamid.OUT0001:delivered");
            assertThat(event.type()).isEqualTo(WebhookEventType.STATUS);
            assertThat(event.waId()).isEqualTo("5516990000001");
            assertThat(event.payloadJson()).contains("\"category\":\"service\"");
        });
    }

    @Test
    void parsesCoexistenceEchoUsingRecipientAsCustomer() {
        List<WebhookEvent> events = parser.parse(fixture("message-echo.json"));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.eventKey()).isEqualTo("echo:wamid.ECHO0001");
            assertThat(event.type()).isEqualTo(WebhookEventType.ECHO);
            assertThat(event.field()).isEqualTo("smb_message_echoes");
            assertThat(event.waId()).isEqualTo("5516990000001");
        });
    }

    @Test
    void keepsOtherFieldsWithStableContentHashKey() {
        List<WebhookEvent> first = parser.parse(fixture("account-update.json"));
        List<WebhookEvent> redelivery = parser.parse(fixture("account-update.json"));

        assertThat(first).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(WebhookEventType.OTHER);
            assertThat(event.field()).isEqualTo("account_update");
            assertThat(event.eventKey()).matches("account_update:[0-9a-f]{64}");
            assertThat(event.phoneNumberId()).isNull();
            assertThat(event.payloadJson()).contains("PARTNER_REMOVED");
        });
        assertThat(redelivery.getFirst().eventKey()).isEqualTo(first.getFirst().eventKey());
    }

    @Test
    void rejectsInvalidJson() {
        assertThatThrownBy(() -> parser.parse("{not json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidWebhookPayloadException.class);
    }

    @Test
    void rejectsObjectsOtherThanWhatsAppBusinessAccount() {
        byte[] body = "{\"object\":\"page\",\"entry\":[]}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> parser.parse(body)).isInstanceOf(InvalidWebhookPayloadException.class);
    }

    static byte[] fixture(String name) {
        try (InputStream in = WebhookPayloadParserTest.class.getResourceAsStream("/whatsapp/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("Fixture not found: " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
