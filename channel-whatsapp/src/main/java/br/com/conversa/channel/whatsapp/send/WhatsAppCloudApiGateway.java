package br.com.conversa.channel.whatsapp.send;

import br.com.conversa.core.channel.ChannelGateway;
import br.com.conversa.core.channel.OutboundMessage;
import br.com.conversa.core.channel.SendResult;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Sends messages through {@code POST /{phone_number_id}/messages} of the Graph API.
 */
public final class WhatsAppCloudApiGateway implements ChannelGateway {

    /** Meta error codes worth retrying: rate limits and temporary outages. */
    private static final Set<Integer> RETRYABLE_CODES = Set.of(4, 80007, 130429, 131000, 131016, 131056, 133004);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final String messagesUrlPrefix;
    private final UnaryOperator<String> accessTokenForSender;
    private final HttpClient http;
    private final ObjectMapper mapper;

    /** Every number uses the same token. */
    public WhatsAppCloudApiGateway(String graphApiUrl, String graphApiVersion, String accessToken,
                                   HttpClient http, ObjectMapper mapper) {
        this(graphApiUrl, graphApiVersion, senderId -> accessToken, http, mapper);
    }

    /**
     * @param accessTokenForSender token of each sending number ({@code phone_number_id}); blank or
     *                             null fails the call without contacting Meta
     */
    public WhatsAppCloudApiGateway(String graphApiUrl, String graphApiVersion,
                                   UnaryOperator<String> accessTokenForSender, HttpClient http, ObjectMapper mapper) {
        this.messagesUrlPrefix = stripTrailingSlash(graphApiUrl) + "/" + graphApiVersion + "/";
        this.accessTokenForSender = accessTokenForSender;
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public SendResult send(String senderId, OutboundMessage message) {
        ObjectNode body = base();
        body.put("recipient_type", "individual");
        body.put("to", message.to());
        switch (message) {
            case OutboundMessage.Text text -> {
                body.put("type", "text");
                body.putObject("text").put("preview_url", false).put("body", text.body());
            }
            case OutboundMessage.Template template -> {
                body.put("type", "template");
                ObjectNode node = body.putObject("template");
                node.put("name", template.name());
                node.putObject("language").put("code", template.languageCode());
                if (!template.bodyParameters().isEmpty()) {
                    ArrayNode parameters = node.putArray("components").addObject()
                            .put("type", "body").putArray("parameters");
                    template.bodyParameters().forEach(value ->
                            parameters.addObject().put("type", "text").put("text", value));
                }
            }
            case OutboundMessage.Buttons buttons -> {
                body.put("type", "interactive");
                ObjectNode interactive = body.putObject("interactive").put("type", "button");
                interactive.putObject("body").put("text", buttons.body());
                ArrayNode replies = interactive.putObject("action").putArray("buttons");
                buttons.buttons().forEach(button -> replies.addObject().put("type", "reply").putObject("reply")
                        .put("id", button.id()).put("title", button.title()));
            }
        }
        return post(senderId, body);
    }

    @Override
    public SendResult markAsRead(String senderId, String messageId) {
        ObjectNode body = base();
        body.put("status", "read");
        body.put("message_id", messageId);
        return post(senderId, body);
    }

    private ObjectNode base() {
        return mapper.createObjectNode().put("messaging_product", "whatsapp");
    }

    private SendResult post(String senderId, ObjectNode body) {
        String accessToken = accessTokenForSender.apply(senderId);
        if (accessToken == null || accessToken.isBlank()) {
            return new SendResult.Failed(false, "NOT_CONFIGURED", "no access token for this sending number");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(messagesUrlPrefix + senderId + "/messages"))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new SendResult.Failed(true, "IO_ERROR", e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SendResult.Failed(true, "INTERRUPTED", "request interrupted");
        }
        return toResult(response);
    }

    private SendResult toResult(HttpResponse<String> response) {
        JsonNode json = readOrMissing(response.body());
        int status = response.statusCode();
        if (status / 100 == 2) {
            JsonNode id = json.path("messages").path(0).path("id");
            return new SendResult.Sent(id.isMissingNode() || id.isNull() ? null : id.asString());
        }
        JsonNode error = json.path("error");
        int code = error.path("code").asInt(-1);
        boolean retryable = status == 429 || status >= 500 || RETRYABLE_CODES.contains(code);
        String detail = error.path("message").asString("HTTP " + status);
        return new SendResult.Failed(retryable, code == -1 ? "HTTP_" + status : String.valueOf(code), detail);
    }

    private JsonNode readOrMissing(String body) {
        try {
            return body == null || body.isBlank() ? mapper.missingNode() : mapper.readTree(body);
        } catch (JacksonException e) {
            return mapper.missingNode();
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
