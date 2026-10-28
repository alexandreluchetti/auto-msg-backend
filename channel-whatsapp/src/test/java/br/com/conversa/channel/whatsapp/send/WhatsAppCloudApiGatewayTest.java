package br.com.conversa.channel.whatsapp.send;

import br.com.conversa.core.channel.OutboundMessage;
import br.com.conversa.core.channel.SendResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WhatsAppCloudApiGatewayTest {

    private static final String PHONE_NUMBER_ID = "200000000000001";
    private static final String CUSTOMER = "5516990000001";

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private FakeGraphApi graph;
    private WhatsAppCloudApiGateway gateway;

    @BeforeEach
    void setUp() {
        graph = new FakeGraphApi();
        gateway = gatewayWithToken("test-token");
    }

    @AfterEach
    void tearDown() {
        graph.close();
    }

    @Test
    void sendsTextMessageAndReturnsWamid() {
        SendResult result = gateway.send(PHONE_NUMBER_ID, new OutboundMessage.Text(CUSTOMER, "Olá!"));

        assertThat(result).isEqualTo(new SendResult.Sent("wamid.OUT0001"));
        FakeGraphApi.Request request = graph.requests().getFirst();
        assertThat(request.path()).isEqualTo("/v24.0/" + PHONE_NUMBER_ID + "/messages");
        assertThat(request.authorization()).isEqualTo("Bearer test-token");
        JsonNode body = mapper.readTree(request.body());
        assertThat(body.path("messaging_product").asString()).isEqualTo("whatsapp");
        assertThat(body.path("to").asString()).isEqualTo(CUSTOMER);
        assertThat(body.path("type").asString()).isEqualTo("text");
        assertThat(body.path("text").path("body").asString()).isEqualTo("Olá!");
    }

    @Test
    void sendsTemplateWithBodyParameters() {
        gateway.send(PHONE_NUMBER_ID,
                new OutboundMessage.Template(CUSTOMER, "pedido_saiu_para_entrega", "pt_BR", List.of("Ana", "#42")));

        JsonNode template = mapper.readTree(graph.requests().getFirst().body()).path("template");
        assertThat(template.path("name").asString()).isEqualTo("pedido_saiu_para_entrega");
        assertThat(template.path("language").path("code").asString()).isEqualTo("pt_BR");
        JsonNode parameters = template.path("components").path(0).path("parameters");
        assertThat(template.path("components").path(0).path("type").asString()).isEqualTo("body");
        assertThat(parameters.path(0).path("text").asString()).isEqualTo("Ana");
        assertThat(parameters.path(1).path("text").asString()).isEqualTo("#42");
    }

    @Test
    void sendsReplyButtons() {
        gateway.send(PHONE_NUMBER_ID, new OutboundMessage.Buttons(CUSTOMER, "Está tudo certo?", List.of(
                new OutboundMessage.Button("confirm:1", "Confirmar pedido"),
                new OutboundMessage.Button("edit:1", "Alterar"))));

        JsonNode body = mapper.readTree(graph.requests().getFirst().body());
        assertThat(body.path("type").asString()).isEqualTo("interactive");
        JsonNode interactive = body.path("interactive");
        assertThat(interactive.path("type").asString()).isEqualTo("button");
        assertThat(interactive.path("body").path("text").asString()).isEqualTo("Está tudo certo?");
        JsonNode first = interactive.path("action").path("buttons").path(0);
        assertThat(first.path("type").asString()).isEqualTo("reply");
        assertThat(first.path("reply").path("id").asString()).isEqualTo("confirm:1");
        assertThat(first.path("reply").path("title").asString()).isEqualTo("Confirmar pedido");
        assertThat(interactive.path("action").path("buttons")).hasSize(2);
    }

    @Test
    void marksMessageAsRead() {
        graph.respond(200, "{\"success\":true}");

        SendResult result = gateway.markAsRead(PHONE_NUMBER_ID, "wamid.TEXT0001");

        assertThat(result).isEqualTo(new SendResult.Sent(null));
        JsonNode body = mapper.readTree(graph.requests().getFirst().body());
        assertThat(body.path("status").asString()).isEqualTo("read");
        assertThat(body.path("message_id").asString()).isEqualTo("wamid.TEXT0001");
    }

    @Test
    void windowClosedErrorIsPermanent() {
        graph.respond(400, error(131047, "Re-engagement message"));

        SendResult result = gateway.send(PHONE_NUMBER_ID, new OutboundMessage.Text(CUSTOMER, "oi"));

        assertThat(result).isEqualTo(new SendResult.Failed(false, "131047", "Re-engagement message"));
    }

    @Test
    void rateLimitAndServerErrorsAreRetryable() {
        graph.respond(400, error(130429, "Rate limit hit"));
        assertThat(gateway.send(PHONE_NUMBER_ID, new OutboundMessage.Text(CUSTOMER, "oi")))
                .isEqualTo(new SendResult.Failed(true, "130429", "Rate limit hit"));

        graph.respond(503, "");
        assertThat(gateway.send(PHONE_NUMBER_ID, new OutboundMessage.Text(CUSTOMER, "oi")))
                .isEqualTo(new SendResult.Failed(true, "HTTP_503", "HTTP 503"));
    }

    @Test
    void unreachableApiIsRetryable() {
        String url = graph.baseUrl();
        graph.close();
        WhatsAppCloudApiGateway offline = new WhatsAppCloudApiGateway(url, "v24.0", "test-token",
                HttpClient.newHttpClient(), mapper);

        SendResult result = offline.send(PHONE_NUMBER_ID, new OutboundMessage.Text(CUSTOMER, "oi"));

        assertThat(result).isInstanceOfSatisfying(SendResult.Failed.class, failed -> {
            assertThat(failed.retryable()).isTrue();
            assertThat(failed.code()).isEqualTo("IO_ERROR");
        });
    }

    @Test
    void missingAccessTokenFailsWithoutCallingTheApi() {
        SendResult result = gatewayWithToken(" ").send(PHONE_NUMBER_ID, new OutboundMessage.Text(CUSTOMER, "oi"));

        assertThat(result).isInstanceOfSatisfying(SendResult.Failed.class,
                failed -> assertThat(failed.code()).isEqualTo("NOT_CONFIGURED"));
        assertThat(graph.requests()).isEmpty();
    }

    private WhatsAppCloudApiGateway gatewayWithToken(String token) {
        return new WhatsAppCloudApiGateway(graph.baseUrl() + "/", "v24.0", token, HttpClient.newHttpClient(), mapper);
    }

    private static String error(int code, String message) {
        return "{\"error\":{\"message\":\"" + message + "\",\"type\":\"OAuthException\",\"code\":" + code
                + ",\"fbtrace_id\":\"AbC\"}}";
    }
}
