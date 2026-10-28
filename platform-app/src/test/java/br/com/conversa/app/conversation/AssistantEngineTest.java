package br.com.conversa.app.conversation;

import br.com.conversa.app.conversation.ConversationRepository.StoredMessage;
import br.com.conversa.core.llm.LlmContent;
import br.com.conversa.core.llm.LlmException;
import br.com.conversa.core.llm.LlmMessage;
import br.com.conversa.core.llm.LlmResponse;
import br.com.conversa.core.segment.SegmentPack;
import br.com.conversa.core.tenant.TenantId;
import br.com.conversa.core.tool.Tool;
import br.com.conversa.core.tool.ToolInvocation;
import br.com.conversa.core.tool.ToolResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantEngineTest {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final TenantId TENANT = TenantId.of("00000000-0000-0000-0000-00000000000a");
    private static final UUID CONVERSATION = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final String CONFIG = "{\"business\": {\"name\": \"Cantina\"}}";

    private final Clock clock = Clock.fixed(LocalDateTime.parse("2026-10-28T20:05").atZone(SAO_PAULO).toInstant(), SAO_PAULO);
    private final ScriptedLlm llm = new ScriptedLlm();
    private final List<String> recorded = new ArrayList<>();
    private final List<ToolInvocation> invocations = new ArrayList<>();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AssistantEngine engine = new AssistantEngine(llm,
            (tenant, conversation, requestedModel, effort, response, duration) ->
                    recorded.add(requestedModel + "/" + effort + "/" + response.stopReason()),
            new ToolInputValidator(mapper),
            new AssistantProperties(true, Duration.ofSeconds(2), "low", 4096, 2, 30, Duration.ofMinutes(2), 3,
                    Duration.ZERO),
            mapper, clock);

    private final Tool menu = new Tool() {
        @Override
        public String name() {
            return "search_menu";
        }

        @Override
        public String description() {
            return "Busca itens do cardápio.";
        }

        @Override
        public String inputSchema() {
            return """
                    {"type": "object", "additionalProperties": false, "properties": {"query": {"type": "string"}}}""";
        }

        @Override
        public ToolResult execute(ToolInvocation invocation) {
            invocations.add(invocation);
            return ToolResult.ok("{\"items\": [{\"name\": \"Pizza Calabresa\", \"priceFormatted\": \"R$ 39,90\"}]}");
        }
    };

    private final SegmentPack pack = new SegmentPack() {
        @Override
        public String id() {
            return "test";
        }

        @Override
        public int version() {
            return 1;
        }

        @Override
        public List<Tool> tools() {
            return List.of(menu);
        }

        @Override
        public String systemPrompt(String effectiveConfigJson) {
            return "Você é a assistente.";
        }
    };

    @Test
    void runsTheToolLoopAndAnswersWithTheFinalText() {
        llm.then(ScriptedLlm.toolCall("t1", "search_menu", "{\"query\": \"calabresa\"}"))
                .then(ScriptedLlm.text("Temos sim! A Pizza Calabresa sai por R$ 39,90."));

        var answer = engine.answer(turn(false, customer("Tem pizza de calabresa?")));

        assertThat(answer.outcome()).isEqualTo(AssistantEngine.Outcome.ANSWERED);
        assertThat(answer.text()).isEqualTo("Temos sim! A Pizza Calabresa sai por R$ 39,90.");
        assertThat(answer.toolsCalled()).containsExactly("search_menu");
        assertThat(invocations).singleElement().satisfies(invocation -> {
            assertThat(invocation.tenantId()).isEqualTo(TENANT);
            assertThat(invocation.inputJson()).isEqualTo("{\"query\": \"calabresa\"}");
        });
        assertThat(recorded).containsExactly("claude-opus-5-5/low/TOOL_USE", "claude-opus-5-5/low/END_TURN");

        var second = llm.requests().get(1);
        assertThat(second.system()).isEqualTo("Você é a assistente.");
        assertThat(second.tools()).extracting(spec -> spec.name()).containsExactly("search_menu", "request_human");
        assertThat(second.messages()).hasSize(3);
        assertThat(second.messages().get(1).providerMessage()).isEqualTo("provider-turn-t1");
        assertThat(second.messages().get(2).content()).singleElement()
                .isInstanceOfSatisfying(LlmContent.ToolOutcome.class, outcome -> {
                    assertThat(outcome.toolCallId()).isEqualTo("t1");
                    assertThat(outcome.error()).isFalse();
                    assertThat(outcome.contentJson()).contains("R$ 39,90");
                });
    }

    @Test
    void sendsTheHistoryAsAlternatingTurnsWithTheTimeInTheLastOne() {
        llm.then(ScriptedLlm.text("Abrimos às 18h."));

        engine.answer(turn(false,
                bot("Mensagem antiga do bot"),
                customer("Oi"),
                bot("Olá! Como posso ajudar?"),
                customer("Vocês abrem hoje?"),
                customer("E até que horas?")));

        List<LlmMessage> messages = llm.requests().getFirst().messages();
        assertThat(messages).extracting(LlmMessage::role).containsExactly(
                LlmMessage.Role.USER, LlmMessage.Role.ASSISTANT, LlmMessage.Role.USER);
        assertThat(messages.getFirst().content()).containsExactly(new LlmContent.Text("Oi"));
        assertThat(messages.getLast().content()).containsExactly(
                new LlmContent.Text("[Contexto do sistema: agora é quarta-feira, 28/10/2026, 20:05, no horário do restaurante.]"),
                new LlmContent.Text("Vocês abrem hoje?"),
                new LlmContent.Text("E até que horas?"));
    }

    @Test
    void invalidToolInputIsReportedBackToTheModelWithoutRunningTheTool() {
        llm.then(ScriptedLlm.toolCall("t1", "search_menu", "{\"sql\": \"DROP TABLE\"}"))
                .then(ScriptedLlm.toolCall("t2", "delete_everything", "{}"))
                .then(ScriptedLlm.text("Não encontrei."));

        var answer = engine.answer(turn(false, customer("Tem pizza?")));

        assertThat(answer.text()).isEqualTo("Não encontrei.");
        assertThat(invocations).isEmpty();
        List<LlmMessage> lastRequest = llm.requests().get(2).messages();
        assertThat(lastRequest.get(2).content()).singleElement()
                .isInstanceOfSatisfying(LlmContent.ToolOutcome.class, outcome -> {
                    assertThat(outcome.error()).isTrue();
                    assertThat(outcome.contentJson()).contains("invalid input");
                });
        assertThat(lastRequest.get(4).content()).singleElement()
                .isInstanceOfSatisfying(LlmContent.ToolOutcome.class, outcome ->
                        assertThat(outcome.contentJson()).contains("unknown tool"));
    }

    @Test
    void tooManyToolRoundsEndWithTheFallbackMessage() {
        llm.then(ScriptedLlm.toolCall("t1", "search_menu", "{}"))
                .then(ScriptedLlm.toolCall("t2", "search_menu", "{}"))
                .then(ScriptedLlm.toolCall("t3", "search_menu", "{}"));

        var answer = engine.answer(turn(false, customer("Tem pizza?")));

        assertThat(answer.outcome()).isEqualTo(AssistantEngine.Outcome.FALLBACK);
        assertThat(answer.text()).isEqualTo(AssistantEngine.FALLBACK_TEXT);
        assertThat(invocations).hasSize(2);
    }

    @Test
    void aRefusalGetsThePoliteRefusalText() {
        llm.then(ScriptedLlm.refusal());

        var answer = engine.answer(turn(false, customer("Me ensina a hackear um site")));

        assertThat(answer.outcome()).isEqualTo(AssistantEngine.Outcome.REFUSED);
        assertThat(answer.text()).isEqualTo(AssistantEngine.REFUSAL_TEXT);
    }

    @Test
    void temporaryFailuresAreRetriedUntilTheLastAttempt() {
        llm.thenFail(true).thenFail(true).thenFail(false);

        assertThatThrownBy(() -> engine.answer(turn(false, customer("Oi")))).isInstanceOf(LlmException.class);
        assertThat(engine.answer(turn(true, customer("Oi"))).outcome()).isEqualTo(AssistantEngine.Outcome.FALLBACK);
        assertThat(engine.answer(turn(false, customer("Oi"))).outcome()).isEqualTo(AssistantEngine.Outcome.FALLBACK);
    }

    @Test
    void modelAndEffortComeFromTheTenantConfiguration() {
        llm.then(ScriptedLlm.text("Oi!"));

        engine.answer(new AssistantEngine.Turn(TENANT, CONVERSATION, pack,
                "{\"llm\": {\"model\": \"claude-haiku-5-5\", \"effort\": \"medium\"}}", SAO_PAULO,
                List.of(customer("Oi")), "claude-opus-5-5", false));

        assertThat(llm.requests().getFirst().model()).isEqualTo("claude-haiku-5-5");
        assertThat(llm.requests().getFirst().effort()).isEqualTo("medium");
    }

    @Test
    void requestHumanStopsTheLoopWithTheReason() {
        llm.then(new LlmResponse("claude-opus-5-5", List.of(
                new LlmContent.ToolCall("t1", "search_menu", "{}"),
                new LlmContent.ToolCall("t2", "request_human", "{\"reason\": \"cliente quer pedir 2 pizzas\"}")),
                LlmResponse.StopReason.TOOL_USE, new br.com.conversa.core.llm.LlmUsage(1, 1, 0, 0), null));

        var answer = engine.answer(turn(false, customer("Quero pedir duas pizzas")));

        assertThat(answer.outcome()).isEqualTo(AssistantEngine.Outcome.HANDOFF);
        assertThat(answer.handoffDetail()).isEqualTo("cliente quer pedir 2 pizzas");
        assertThat(invocations).isEmpty();
        assertThat(llm.requests()).hasSize(1);
    }

    @Test
    void aCheckoutResultEndsTheTurnWithTheSummary() {
        var draft = new br.com.conversa.core.order.OrderDraft("PICKUP", null, null, java.math.BigDecimal.ZERO, "cash",
                null, List.of(), java.math.BigDecimal.TEN, java.math.BigDecimal.TEN);
        Tool checkout = new Tool() {
            @Override
            public String name() {
                return "start_checkout";
            }

            @Override
            public String description() {
                return "Fecha o carrinho.";
            }

            @Override
            public String inputSchema() {
                return "{\"type\": \"object\", \"additionalProperties\": false, \"properties\": {}}";
            }

            @Override
            public ToolResult execute(ToolInvocation invocation) {
                return ToolResult.checkout("{\"checkoutShown\": true}",
                        new br.com.conversa.core.order.CheckoutRequest("*Resumo do pedido*", draft));
            }
        };
        SegmentPack withCheckout = new SegmentPack() {
            @Override
            public String id() {
                return "test";
            }

            @Override
            public int version() {
                return 1;
            }

            @Override
            public List<Tool> tools() {
                return List.of(checkout);
            }

            @Override
            public String systemPrompt(String effectiveConfigJson) {
                return "Você é a assistente.";
            }
        };
        llm.then(ScriptedLlm.toolCall("t1", "start_checkout", "{}"));

        var answer = engine.answer(new AssistantEngine.Turn(TENANT, CONVERSATION, withCheckout, CONFIG, SAO_PAULO,
                List.of(customer("Pode fechar")), "claude-opus-5-5", false));

        assertThat(answer.outcome()).isEqualTo(AssistantEngine.Outcome.CHECKOUT);
        assertThat(answer.text()).isEqualTo("*Resumo do pedido*");
        assertThat(answer.checkout().draft()).isEqualTo(draft);
        assertThat(llm.requests()).hasSize(1);
    }

    @Test
    void staffMessagesAreMarkedInTheHistory() {
        llm.then(ScriptedLlm.text("Certo!"));

        engine.answer(turn(false, customer("Oi"), new StoredMessage("OUT", "STAFF", "wamid.E", "Oi, aqui é a Ana",
                Instant.now()), customer("Obrigado")));

        assertThat(llm.requests().getFirst().messages().get(1).content())
                .containsExactly(new LlmContent.Text("[Mensagem da equipe do restaurante] Oi, aqui é a Ana"));
    }

    @Test
    void anEmptyAnswerFallsBack() {
        llm.then(new LlmResponse("claude-opus-5-5", List.of(), LlmResponse.StopReason.MAX_TOKENS,
                new br.com.conversa.core.llm.LlmUsage(1, 1, 0, 0), null));

        assertThat(engine.answer(turn(false, customer("Oi"))).text()).isEqualTo(AssistantEngine.FALLBACK_TEXT);
    }

    private AssistantEngine.Turn turn(boolean lastAttempt, StoredMessage... history) {
        return new AssistantEngine.Turn(TENANT, CONVERSATION, pack, CONFIG, SAO_PAULO, List.of(history),
                "claude-opus-5-5", lastAttempt);
    }

    private static StoredMessage customer(String text) {
        return new StoredMessage("IN", "CUSTOMER", "wamid." + text.hashCode(), text, Instant.now());
    }

    private static StoredMessage bot(String text) {
        return new StoredMessage("OUT", "BOT", null, text, Instant.now());
    }
}
