package br.com.conversa.llm.anthropic;

import br.com.conversa.core.llm.LlmContent;
import br.com.conversa.core.llm.LlmException;
import br.com.conversa.core.llm.LlmMessage;
import br.com.conversa.core.llm.LlmRequest;
import br.com.conversa.core.llm.LlmResponse;
import br.com.conversa.core.llm.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wire format of the adapter against a fake Messages API (never the real one).
 */
class AnthropicLlmClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String TOOL_USE_RESPONSE = """
            {"id": "msg_1", "type": "message", "role": "assistant", "model": "claude-opus-5-5",
             "content": [
               {"type": "thinking", "thinking": "", "signature": "sig-abc"},
               {"type": "text", "text": "Vou verificar."},
               {"type": "tool_use", "id": "toolu_1", "name": "search_menu", "input": {"query": "calabresa"}}],
             "stop_reason": "tool_use", "stop_sequence": null,
             "usage": {"input_tokens": 1200, "output_tokens": 80, "cache_read_input_tokens": 3000,
                       "cache_creation_input_tokens": 500}}
            """;

    private HttpServer server;
    private final List<JsonNode> bodies = new ArrayList<>();
    private final List<Map<String, String>> headers = new ArrayList<>();
    private final Map<String, Object> next = new ConcurrentHashMap<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            bodies.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            headers.add(Map.of(
                    "x-api-key", String.valueOf(exchange.getRequestHeaders().getFirst("x-api-key")),
                    "anthropic-beta", String.valueOf(exchange.getRequestHeaders().getFirst("anthropic-beta"))));
            int status = (int) next.getOrDefault("status", 200);
            byte[] response = ((String) next.getOrDefault("body", TOOL_USE_RESPONSE)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.getResponseHeaders().add("retry-after-ms", "1");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void sendsCustomerImagesAsBase64Blocks() {
        client(true).complete(new LlmRequest("claude-opus-5-5", "low", 2048, "Você é a assistente.", tools(),
                List.of(new LlmMessage(LlmMessage.Role.USER, List.of(new LlmContent.Text("[imagem] tem esse?"),
                        new LlmContent.Image("image/jpeg", new byte[]{1, 2, 3}))))));

        JsonNode image = bodies.getFirst().path("messages").path(0).path("content").path(1);
        assertThat(image.path("type").asText()).isEqualTo("image");
        assertThat(image.path("source").path("type").asText()).isEqualTo("base64");
        assertThat(image.path("source").path("media_type").asText()).isEqualTo("image/jpeg");
        assertThat(image.path("source").path("data").asText()).isEqualTo("AQID");
    }

    @Test
    void sendsCachedSystemToolsEffortAndRefusalFallback() {
        client(true).complete(request("claude-opus-5-5"));

        JsonNode body = bodies.getFirst();
        assertThat(headers.getFirst().get("x-api-key")).isEqualTo("test-key");
        assertThat(headers.getFirst().get("anthropic-beta")).contains(AnthropicLlmClient.FALLBACK_BETA);
        assertThat(body.path("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(2048);
        assertThat(body.path("system").path(0).path("text").asText()).isEqualTo("Você é a assistente.");
        assertThat(body.path("system").path(0).path("cache_control").path("type").asText()).isEqualTo("ephemeral");
        assertThat(body.path("cache_control").path("type").asText()).isEqualTo("ephemeral");
        assertThat(body.path("output_config").path("effort").asText()).isEqualTo("low");
        assertThat(body.path("fallbacks").asText()).isEqualTo("default");
        assertThat(body.has("temperature")).isFalse();
        assertThat(body.has("thinking")).isFalse();
        assertThat(body.has("tool_choice")).isFalse();
        JsonNode tool = body.path("tools").path(0);
        assertThat(tool.path("name").asText()).isEqualTo("search_menu");
        assertThat(tool.path("input_schema").path("type").asText()).isEqualTo("object");
        assertThat(tool.path("input_schema").path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(tool.path("input_schema").path("properties").path("query").path("type").asText()).isEqualTo("string");
        assertThat(body.path("messages").path(0).path("role").asText()).isEqualTo("user");
        assertThat(body.path("messages").path(0).path("content").path(0).path("text").asText()).isEqualTo("Tem calabresa?");
    }

    @Test
    void noFallbackForModelsWithoutIt() {
        client(true).complete(request("claude-haiku-5-5"));
        client(false).complete(request("claude-opus-5-5"));

        assertThat(bodies).allSatisfy(body -> assertThat(body.has("fallbacks")).isFalse());
    }

    @Test
    void readsToolCallsTextAndUsage() {
        LlmResponse response = client(true).complete(request("claude-opus-5-5"));

        assertThat(response.stopReason()).isEqualTo(LlmResponse.StopReason.TOOL_USE);
        assertThat(response.model()).isEqualTo("claude-opus-5-5");
        assertThat(response.text()).isEqualTo("Vou verificar.");
        assertThat(response.toolCalls()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("toolu_1");
            assertThat(call.name()).isEqualTo("search_menu");
            assertThat(call.inputJson()).isEqualTo("{\"query\":\"calabresa\"}");
        });
        assertThat(response.usage().inputTokens()).isEqualTo(1200);
        assertThat(response.usage().cacheReadTokens()).isEqualTo(3000);
        assertThat(response.usage().cacheWriteTokens()).isEqualTo(500);
    }

    @Test
    void replaysTheAssistantTurnWithItsThinkingBlockAndSendsToolResults() {
        AnthropicLlmClient client = client(true);
        LlmResponse first = client.complete(request("claude-opus-5-5"));

        next.put("body", """
                {"id": "msg_2", "type": "message", "role": "assistant", "model": "claude-opus-5-5",
                 "content": [{"type": "text", "text": "Temos sim: R$ 39,90."}],
                 "stop_reason": "end_turn", "stop_sequence": null,
                 "usage": {"input_tokens": 10, "output_tokens": 5}}
                """);
        LlmRequest followUp = new LlmRequest("claude-opus-5-5", "low", 2048, "Você é a assistente.", tools(), List.of(
                LlmMessage.user("Tem calabresa?"),
                first.asAssistantMessage(),
                new LlmMessage(LlmMessage.Role.USER, List.of(new LlmContent.ToolOutcome("toolu_1", "{\"items\":[]}", false)))));
        LlmResponse second = client.complete(followUp);

        JsonNode messages = bodies.get(1).path("messages");
        JsonNode assistant = messages.path(1).path("content");
        assertThat(assistant.path(0).path("type").asText()).isEqualTo("thinking");
        assertThat(assistant.path(0).path("signature").asText()).isEqualTo("sig-abc");
        assertThat(assistant.path(2).path("type").asText()).isEqualTo("tool_use");
        JsonNode result = messages.path(2).path("content").path(0);
        assertThat(result.path("type").asText()).isEqualTo("tool_result");
        assertThat(result.path("tool_use_id").asText()).isEqualTo("toolu_1");
        assertThat(second.stopReason()).isEqualTo(LlmResponse.StopReason.END_TURN);
        assertThat(second.usage().cacheReadTokens()).isZero();
    }

    @Test
    void reportsRefusals() {
        next.put("body", """
                {"id": "msg_3", "type": "message", "role": "assistant", "model": "claude-opus-5-5",
                 "content": [], "stop_reason": "refusal", "stop_sequence": null,
                 "stop_details": {"type": "refusal", "category": null, "explanation": null},
                 "usage": {"input_tokens": 10, "output_tokens": 0}}
                """);

        assertThat(client(true).complete(request("claude-opus-5-5")).stopReason())
                .isEqualTo(LlmResponse.StopReason.REFUSAL);
    }

    @Test
    void classifiesErrors() {
        next.put("status", 429);
        next.put("body", "{\"type\": \"error\", \"error\": {\"type\": \"rate_limit_error\", \"message\": \"slow down\"}}");
        assertThatThrownBy(() -> client(true).complete(request("claude-opus-5-5")))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.retryable()).isTrue());

        next.put("status", 400);
        next.put("body", "{\"type\": \"error\", \"error\": {\"type\": \"invalid_request_error\", \"message\": \"bad\"}}");
        assertThatThrownBy(() -> client(true).complete(request("claude-opus-5-5")))
                .isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.retryable()).isFalse());
    }

    @Test
    void withoutAKeyItIsNotConfigured() {
        AnthropicLlmClient client = new AnthropicLlmClient(
                new AnthropicProperties("", "claude-opus-5-5", null, Duration.ofSeconds(5), true));

        assertThat(client.configured()).isFalse();
        assertThatThrownBy(() -> client.complete(request("claude-opus-5-5"))).isInstanceOf(LlmException.class);
    }

    private AnthropicLlmClient client(boolean fallback) {
        return new AnthropicLlmClient(new AnthropicProperties("test-key", "claude-opus-5-5",
                "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(5), fallback));
    }

    private static LlmRequest request(String model) {
        return new LlmRequest(model, "low", 2048, "Você é a assistente.", tools(), List.of(LlmMessage.user("Tem calabresa?")));
    }

    private static List<ToolSpec> tools() {
        return List.of(new ToolSpec("search_menu", "Busca itens do cardápio.", """
                {"type": "object", "additionalProperties": false, "properties": {"query": {"type": "string"}}}"""));
    }
}
