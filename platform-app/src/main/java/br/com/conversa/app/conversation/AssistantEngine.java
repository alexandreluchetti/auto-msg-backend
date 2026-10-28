package br.com.conversa.app.conversation;

import br.com.conversa.app.conversation.ConversationRepository.StoredMessage;
import br.com.conversa.core.llm.LlmClient;
import br.com.conversa.core.llm.LlmContent;
import br.com.conversa.core.llm.LlmException;
import br.com.conversa.core.llm.LlmMessage;
import br.com.conversa.core.llm.LlmRequest;
import br.com.conversa.core.llm.LlmResponse;
import br.com.conversa.core.llm.ToolSpec;
import br.com.conversa.core.segment.SegmentPack;
import br.com.conversa.core.tenant.TenantId;
import br.com.conversa.core.tool.Tool;
import br.com.conversa.core.tool.ToolInvocation;
import br.com.conversa.core.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One answer of the bot (ADR-002, step 3): the Segment Pack's system prompt and tools, the latest
 * messages as history, and the tool loop. Tools are the only source of business facts; their input
 * is validated against their schema before they run.
 *
 * <p>Requests stay cache-friendly: the system prompt and tools depend only on the tenant
 * configuration, earlier turns are plain text, and the volatile context (current time) is a block
 * of the last customer message. Within the tool loop each model turn is replayed unchanged.
 */
@Component
public class AssistantEngine {

    private static final Logger log = LoggerFactory.getLogger(AssistantEngine.class);

    static final String FALLBACK_TEXT = "Desculpe, não consegui responder agora. Pode mandar sua mensagem de novo "
            + "daqui a pouco? A equipe do restaurante também acompanha esta conversa.";
    static final String REFUSAL_TEXT = "Desculpe, não posso ajudar com isso. Posso ajudar com o cardápio, "
            + "horários, entrega ou formas de pagamento?";
    /**
     * Control tool every pack gets (ADR-002): the assistant hands the conversation to staff. The
     * engine stops at the call; the handoff itself is deterministic ({@link HandoffService}).
     */
    static final ToolSpec REQUEST_HUMAN = new ToolSpec("request_human", """
            Passa a conversa para a equipe do restaurante, que continua o atendimento por esta mesma \
            conversa. Use quando o cliente pedir para falar com uma pessoa, reclamar de um pedido ou \
            de um problema, ou quando você não conseguir ajudar depois de tentar com as outras \
            ferramentas. Depois de chamar, não escreva mais nada: o sistema avisa o cliente.""", """
            {"type": "object", "additionalProperties": false, "required": ["reason"], "properties": {
              "reason": {"type": "string", "description": "Motivo em poucas palavras, para a equipe"}
            }}""");
    private static final int MAX_TEXT = 4000;
    private static final DateTimeFormatter NOW = DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy, HH:mm",
            Locale.of("pt", "BR"));

    /** Where every model call is recorded with its cost. */
    public interface CallLog {
        void record(TenantId tenant, UUID conversation, String requestedModel, String effort, LlmResponse response,
                    Duration duration);
    }

    /** Everything one answer needs; {@code history} ends with the customer's unanswered messages. */
    public record Turn(TenantId tenant, UUID conversationId, SegmentPack pack, String configJson, ZoneId zone,
                       List<StoredMessage> history, String defaultModel, boolean lastAttempt) {
    }

    public enum Outcome { ANSWERED, REFUSED, FALLBACK, HANDOFF }

    /** {@code handoffDetail} is the assistant's reason when the outcome is {@link Outcome#HANDOFF}. */
    public record Answer(String text, Outcome outcome, List<String> toolsCalled, String handoffDetail) {

        Answer(String text, Outcome outcome, List<String> toolsCalled) {
            this(text, outcome, toolsCalled, null);
        }
    }

    private final LlmClient llm;
    private final CallLog calls;
    private final ToolInputValidator validator;
    private final AssistantProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;

    public AssistantEngine(LlmClient llm, CallLog calls, ToolInputValidator validator, AssistantProperties properties,
                           ObjectMapper mapper, Clock clock) {
        this.llm = llm;
        this.calls = calls;
        this.validator = validator;
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * @throws LlmException when the model failed temporarily and this is not the last attempt; the
     *                      caller retries later
     */
    public Answer answer(Turn turn) {
        JsonNode config = mapper.readTree(turn.configJson());
        String model = textOr(config.path("llm").path("model"), turn.defaultModel());
        String effort = textOr(config.path("llm").path("effort"), properties.defaultEffort());
        Map<String, Tool> tools = turn.pack().tools().stream()
                .collect(Collectors.toMap(Tool::name, Function.identity()));
        List<ToolSpec> specs = turn.pack().tools().stream()
                .map(tool -> new ToolSpec(tool.name(), tool.description(), tool.inputSchema()))
                .collect(Collectors.toCollection(ArrayList::new));
        specs.add(REQUEST_HUMAN);
        String system = turn.pack().systemPrompt(turn.configJson());
        List<LlmMessage> messages = new ArrayList<>(history(turn));
        List<String> toolsCalled = new ArrayList<>();

        try {
            for (int round = 0; ; round++) {
                Instant started = clock.instant();
                LlmResponse response = llm.complete(
                        new LlmRequest(model, effort, properties.maxTokens(), system, specs, messages));
                calls.record(turn.tenant(), turn.conversationId(), model, effort, response,
                        Duration.between(started, clock.instant()));

                if (response.stopReason() == LlmResponse.StopReason.REFUSAL) {
                    log.info("Model refused to answer conversation {}", turn.conversationId());
                    return new Answer(REFUSAL_TEXT, Outcome.REFUSED, toolsCalled);
                }
                List<LlmContent.ToolCall> toolCalls = response.toolCalls();
                if (response.stopReason() == LlmResponse.StopReason.TOOL_USE && !toolCalls.isEmpty()) {
                    if (round >= properties.maxToolRounds()) {
                        log.warn("Conversation {} exceeded {} tool rounds", turn.conversationId(), properties.maxToolRounds());
                        return new Answer(FALLBACK_TEXT, Outcome.FALLBACK, toolsCalled);
                    }
                    messages.add(response.asAssistantMessage());
                    var handoff = toolCalls.stream().filter(call -> call.name().equals(REQUEST_HUMAN.name())).findFirst();
                    if (handoff.isPresent()) {
                        toolsCalled.add(REQUEST_HUMAN.name());
                        return new Answer(null, Outcome.HANDOFF, toolsCalled,
                                mapper.readTree(handoff.get().inputJson()).path("reason").asString(""));
                    }
                    List<LlmContent> outcomes = new ArrayList<>();
                    for (LlmContent.ToolCall call : toolCalls) {
                        toolsCalled.add(call.name());
                        outcomes.add(run(turn, tools.get(call.name()), call));
                    }
                    messages.add(new LlmMessage(LlmMessage.Role.USER, outcomes));
                    continue;
                }
                String text = response.text();
                if (text.isEmpty() || response.stopReason() != LlmResponse.StopReason.END_TURN) {
                    log.warn("Conversation {} got no usable answer (stop reason {})", turn.conversationId(),
                            response.stopReason());
                    return new Answer(FALLBACK_TEXT, Outcome.FALLBACK, toolsCalled);
                }
                return new Answer(text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text, Outcome.ANSWERED,
                        toolsCalled);
            }
        } catch (LlmException e) {
            if (e.retryable() && !turn.lastAttempt()) {
                throw e;
            }
            log.warn("Conversation {} answered with the fallback message: {}", turn.conversationId(), e.getMessage());
            return new Answer(FALLBACK_TEXT, Outcome.FALLBACK, toolsCalled);
        }
    }

    private LlmContent run(Turn turn, Tool tool, LlmContent.ToolCall call) {
        if (tool == null) {
            return new LlmContent.ToolOutcome(call.id(), "{\"error\":\"unknown tool " + call.name() + "\"}", true);
        }
        var problem = validator.problem(tool, call.inputJson());
        if (problem.isPresent()) {
            return new LlmContent.ToolOutcome(call.id(),
                    mapper.createObjectNode().put("error", "invalid input: " + problem.get()).toString(), true);
        }
        try {
            ToolResult result = tool.execute(new ToolInvocation(turn.tenant(), turn.conversationId().toString(),
                    call.inputJson()));
            return new LlmContent.ToolOutcome(call.id(), result.contentJson(), result.error());
        } catch (RuntimeException e) {
            log.error("Tool {} failed in conversation {}", call.name(), turn.conversationId(), e);
            return new LlmContent.ToolOutcome(call.id(), "{\"error\":\"the tool failed; try again later\"}", true);
        }
    }

    /**
     * Stored messages as alternating user/assistant turns of plain text, starting with the customer
     * and ending with the unanswered customer messages, preceded by the current time.
     */
    List<LlmMessage> history(Turn turn) {
        List<StoredMessage> stored = new ArrayList<>(turn.history());
        while (!stored.isEmpty() && !stored.getFirst().fromCustomer()) {
            stored.removeFirst();
        }
        List<LlmMessage> messages = new ArrayList<>();
        List<LlmContent> pending = new ArrayList<>();
        Boolean customer = null;
        for (StoredMessage message : stored) {
            if (customer != null && customer != message.fromCustomer()) {
                messages.add(new LlmMessage(customer ? LlmMessage.Role.USER : LlmMessage.Role.ASSISTANT, pending));
                pending = new ArrayList<>();
            }
            customer = message.fromCustomer();
            pending.add(new LlmContent.Text("STAFF".equals(message.author())
                    ? "[Mensagem da equipe do restaurante] " + message.text() : message.text()));
        }
        if (customer == null || !customer) {
            throw new IllegalStateException("No unanswered customer message in conversation " + turn.conversationId());
        }
        String now = NOW.format(clock.instant().atZone(turn.zone()));
        pending.addFirst(new LlmContent.Text("[Contexto do sistema: agora é " + now + ", no horário do restaurante.]"));
        messages.add(new LlmMessage(LlmMessage.Role.USER, pending));
        return messages;
    }

    private static String textOr(JsonNode node, String fallback) {
        return node.isString() && !node.asString().isBlank() ? node.asString() : fallback;
    }
}
