package br.com.conversa.llm.anthropic;

import br.com.conversa.core.llm.LlmClient;
import br.com.conversa.core.llm.LlmContent;
import br.com.conversa.core.llm.LlmException;
import br.com.conversa.core.llm.LlmMessage;
import br.com.conversa.core.llm.LlmRequest;
import br.com.conversa.core.llm.LlmResponse;
import br.com.conversa.core.llm.LlmUsage;
import br.com.conversa.core.llm.ToolSpec;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link LlmClient} over the Claude Messages API with the official SDK (ADR-005).
 *
 * <ul>
 *   <li>The system prompt carries a cache breakpoint, and automatic caching covers the history,
 *       so the stable prefix (tools, system, earlier turns) is read from cache.</li>
 *   <li>Thinking is left to the model's default (adaptive); depth is set by {@code effort}. No
 *       sampling parameters are sent.</li>
 *   <li>An assistant turn the model produced is replayed with {@link Message#toParam()}, so its
 *       reasoning blocks go back unchanged within the tool loop.</li>
 *   <li>Refusals may be retried server-side on a fallback model (beta), where the model supports
 *       it; the response then names the model that answered.</li>
 * </ul>
 */
public class AnthropicLlmClient implements LlmClient {

    static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
    private static final Set<String> MODELS_WITH_FALLBACK = Set.of(
            "claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5");
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final AnthropicClient client;
    private final boolean refusalFallback;

    public AnthropicLlmClient(AnthropicProperties properties) {
        this.refusalFallback = properties.refusalFallback();
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            this.client = null;
            return;
        }
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(properties.apiKey())
                .timeout(properties.timeout())
                .maxRetries(2);
        if (properties.baseUrl() != null && !properties.baseUrl().isBlank()) {
            builder.baseUrl(properties.baseUrl());
        }
        this.client = builder.build();
    }

    @Override
    public boolean configured() {
        return client != null;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        if (client == null) {
            throw new LlmException("Claude API key is not configured", false, null);
        }
        try {
            return toResponse(client.messages().create(toParams(request)));
        } catch (AnthropicServiceException e) {
            int status = e.statusCode();
            throw new LlmException("Claude API returned HTTP " + status, status == 429 || status >= 500, e);
        } catch (AnthropicIoException e) {
            throw new LlmException("Cannot reach the Claude API", true, e);
        } catch (AnthropicException e) {
            throw new LlmException("Claude API call failed: " + e.getMessage(), false, e);
        }
    }

    MessageCreateParams toParams(LlmRequest request) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(request.model())
                .maxTokens(request.maxTokens())
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(request.system())
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()))
                .cacheControl(CacheControlEphemeral.builder().build())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(request.effort())).build());
        request.tools().forEach(tool -> params.addTool(toTool(tool)));
        request.messages().forEach(message -> params.addMessage(toParam(message)));
        if (refusalFallback && MODELS_WITH_FALLBACK.contains(request.model())) {
            params.putAdditionalHeader("anthropic-beta", FALLBACK_BETA);
            params.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return params.build();
    }

    private static Tool toTool(ToolSpec spec) {
        Map<String, Object> schema = readMap(spec.inputSchemaJson());
        Tool.InputSchema.Builder inputSchema = Tool.InputSchema.builder();
        schema.forEach((key, value) -> {
            switch (key) {
                case "type" -> {
                    // always "object", set by the SDK
                }
                case "required" -> {
                    @SuppressWarnings("unchecked")
                    List<String> required = (List<String>) value;
                    inputSchema.required(required);
                }
                default -> inputSchema.putAdditionalProperty(key, JsonValue.from(value));
            }
        });
        return Tool.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(inputSchema.build())
                .build();
    }

    private static MessageParam toParam(LlmMessage message) {
        if (message.providerMessage() instanceof MessageParam provider) {
            return provider;
        }
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (LlmContent content : message.content()) {
            blocks.add(switch (content) {
                case LlmContent.Text text -> ContentBlockParam.ofText(TextBlockParam.builder().text(text.text()).build());
                case LlmContent.Image image -> ContentBlockParam.ofImage(ImageBlockParam.builder()
                        .source(Base64ImageSource.builder()
                                .mediaType(Base64ImageSource.MediaType.of(image.mediaType()))
                                .data(Base64.getEncoder().encodeToString(image.data()))
                                .build())
                        .build());
                case LlmContent.ToolCall call -> ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                        .id(call.id())
                        .name(call.name())
                        .input(JsonValue.from(readMap(call.inputJson())))
                        .build());
                case LlmContent.ToolOutcome outcome -> ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(outcome.toolCallId())
                        .content(outcome.contentJson())
                        .isError(outcome.error())
                        .build());
            });
        }
        return MessageParam.builder()
                .role(message.role() == LlmMessage.Role.USER ? MessageParam.Role.USER : MessageParam.Role.ASSISTANT)
                .contentOfBlockParams(blocks)
                .build();
    }

    private static LlmResponse toResponse(Message message) {
        List<LlmContent> content = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            block.text().ifPresent(text -> content.add(new LlmContent.Text(text.text())));
            block.toolUse().ifPresent(use -> content.add(new LlmContent.ToolCall(use.id(), use.name(), writeJson(use._input()))));
            // Thinking blocks are not needed by the engine; toParam() keeps them for the replay.
        }
        var usage = message.usage();
        return new LlmResponse(
                message.model().asString(),
                content,
                stopReason(message.stopReason().orElse(null)),
                new LlmUsage(usage.inputTokens(), usage.outputTokens(),
                        usage.cacheReadInputTokens().orElse(0L), usage.cacheCreationInputTokens().orElse(0L)),
                message.toParam());
    }

    private static LlmResponse.StopReason stopReason(StopReason reason) {
        if (reason == null) {
            return LlmResponse.StopReason.OTHER;
        }
        if (reason.equals(StopReason.END_TURN)) {
            return LlmResponse.StopReason.END_TURN;
        }
        if (reason.equals(StopReason.TOOL_USE)) {
            return LlmResponse.StopReason.TOOL_USE;
        }
        if (reason.equals(StopReason.MAX_TOKENS)) {
            return LlmResponse.StopReason.MAX_TOKENS;
        }
        if (reason.equals(StopReason.REFUSAL)) {
            return LlmResponse.StopReason.REFUSAL;
        }
        return LlmResponse.StopReason.OTHER;
    }

    private static Map<String, Object> readMap(String json) {
        try {
            return ObjectMappers.jsonMapper().readValue(json == null || json.isBlank() ? "{}" : json, MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Not a JSON object: " + json, e);
        }
    }

    private static String writeJson(JsonValue value) {
        try {
            return ObjectMappers.jsonMapper().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize tool input", e);
        }
    }
}
