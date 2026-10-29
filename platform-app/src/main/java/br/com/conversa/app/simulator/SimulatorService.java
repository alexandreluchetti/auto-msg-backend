package br.com.conversa.app.simulator;

import br.com.conversa.app.compose.HumanPace;
import br.com.conversa.app.compose.ResponseComposer;
import br.com.conversa.app.conversation.AssistantEngine;
import br.com.conversa.app.conversation.ConversationRepository;
import br.com.conversa.app.order.TextConfirmation;
import br.com.conversa.app.persona.PersonaPrompt;
import br.com.conversa.app.persona.PersonaTexts;
import br.com.conversa.app.persona.PromptTemplates;
import br.com.conversa.app.tenant.TenantService;
import br.com.conversa.app.tenant.TenantTransactions;
import br.com.conversa.core.business.BusinessDirectory;
import br.com.conversa.core.segment.SegmentPack;
import br.com.conversa.core.tenant.TenantId;
import br.com.conversa.llm.anthropic.AnthropicProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The panel's WhatsApp-like simulator (ADR-011 step H8, D-013): staff talk to the assistant with
 * the <em>draft</em> persona and settings, the real tools and the real model, and see the answer
 * composed and paced as the customer would. Nothing reaches WhatsApp and no order is created: each
 * staff member has one simulated conversation (history, cart and model costs of its own), kept out
 * of the conversation lists. The deterministic rules of the live flow (erasure, asking for a
 * person) are not simulated; the order confirmation is, without creating the order.
 */
@Service
public class SimulatorService {

    static final String CONFIRM_ID = "sim:confirm";
    static final String EDIT_ID = "sim:edit";
    static final String SIMULATED_ORDER = "Pedido confirmado! _(Simulação: nenhum pedido foi criado e nada foi "
            + "enviado pelo WhatsApp.)_";
    private static final String PHONE_NUMBER_ID = "simulator";

    /** One message of the answer, shown {@code atMs} after the customer's message. */
    public record Bubble(String text, long atMs, List<ResponseComposer.Choice> choices, boolean asButtons) {
    }

    /**
     * @param typingAtMs when the "digitando..." indicator shows, relative to the customer's message
     * @param outcome    what the assistant did (ANSWERED, CHECKOUT, HANDOFF, REFUSED, FALLBACK, CONFIRMED)
     */
    public record Reply(List<Bubble> bubbles, List<Long> typingAtMs, String outcome, List<String> toolsCalled) {
    }

    public record Line(String author, String text, OffsetDateTime sentAt) {
    }

    private final JdbcClient jdbc;
    private final TenantTransactions tenants;
    private final TenantService tenantService;
    private final ConversationRepository conversations;
    private final AssistantEngine engine;
    private final PromptTemplates prompts;
    private final BusinessDirectory directory;
    private final AnthropicProperties anthropic;
    private final Clock clock;

    SimulatorService(JdbcClient jdbc, TenantTransactions tenants, TenantService tenantService,
                     ConversationRepository conversations, AssistantEngine engine, PromptTemplates prompts,
                     BusinessDirectory directory, AnthropicProperties anthropic, Clock clock) {
        this.jdbc = jdbc;
        this.tenants = tenants;
        this.tenantService = tenantService;
        this.conversations = conversations;
        this.engine = engine;
        this.prompts = prompts;
        this.directory = directory;
        this.anthropic = anthropic;
        this.clock = clock;
    }

    /**
     * The staff member says {@code text} (or taps {@code replyId}) as a customer and gets the
     * assistant's answer. Runs the model outside any transaction.
     */
    public Reply say(TenantId tenant, UUID staff, String text, String replyId) {
        UUID conversation = tenants.inTenant(tenant, () -> conversationOf(tenant, staff));
        String customerText = text == null || text.isBlank() ? "[resposta de botão]" : text.strip();
        tenants.runInTenant(tenant, () -> record(tenant, conversation, "IN", "CUSTOMER", customerText, replyId));
        JsonNode config = tenantService.effectiveDraftConfig(tenant);

        boolean confirms = CONFIRM_ID.equals(replyId) || (replyId == null && TextConfirmation.isClear(customerText)
                && tenants.inTenant(tenant, () -> awaitingConfirmation(conversation)));
        if (confirms) {
            tenants.runInTenant(tenant, () -> jdbc.sql("UPDATE conversation SET context = '{}' WHERE id = :id")
                    .param("id", conversation).update());
            return answer(tenant, conversation, SIMULATED_ORDER, List.of(), "CONFIRMED", List.of(), config);
        }

        SegmentPack pack = tenantService.packOf(tenant);
        String prompt = PersonaPrompt.render(prompts.template(tenant, pack, PromptTemplates.Status.DRAFT), config);
        var history = tenants.inTenant(tenant, () -> conversations.recentMessages(conversation, 30));
        var answer = engine.answer(new AssistantEngine.Turn(tenant, conversation, pack, prompt, config.toString(),
                directory.openingHours(tenant).zone(), history, anthropic.defaultModel(), true,
                AssistantEngine.Memory.NONE));
        return switch (answer.outcome()) {
            case CHECKOUT -> {
                tenants.runInTenant(tenant, () -> jdbc.sql("""
                                UPDATE conversation SET context = jsonb_set(context, '{simulatorCheckout}', 'true')
                                WHERE id = :id
                                """)
                        .param("id", conversation).update());
                yield checkout(tenant, conversation, answer, config);
            }
            case HANDOFF -> answer(tenant, conversation, PersonaTexts.handoff(config) + "\n---\n_(Simulação: aqui a "
                    + "conversa iria para a equipe" + (answer.handoffDetail() == null || answer.handoffDetail().isBlank()
                    ? "" : " — motivo: " + answer.handoffDetail()) + ".)_", List.of(), "HANDOFF", answer.toolsCalled(),
                    config);
            default -> answer(tenant, conversation, answer.text(), answer.options(), answer.outcome().name(),
                    answer.toolsCalled(), config);
        };
    }

    /** The staff member's simulated conversation so far, oldest first. */
    public List<Line> history(TenantId tenant, UUID staff) {
        return tenants.inTenant(tenant, () -> jdbc.sql("""
                        SELECT m.author, m.text, m.sent_at FROM message m
                        JOIN conversation c ON c.id = m.conversation_id
                        JOIN contact k ON k.id = c.contact_id
                        WHERE c.simulated AND k.wa_id = :waId
                        ORDER BY m.created_at
                        """)
                .param("waId", waId(staff)).query(Line.class).list());
    }

    /** Starts over: forgets the simulated conversation (messages and cart). */
    public void reset(TenantId tenant, UUID staff) {
        tenants.runInTenant(tenant, () -> jdbc.sql("""
                        DELETE FROM conversation c USING contact k
                        WHERE k.id = c.contact_id AND c.simulated AND k.wa_id = :waId
                        """)
                .param("waId", waId(staff)).update());
    }

    private Reply checkout(TenantId tenant, UUID conversation, AssistantEngine.Answer answer, JsonNode config) {
        List<ResponseComposer.Choice> buttons = List.of(new ResponseComposer.Choice(CONFIRM_ID, "Confirmar pedido"),
                new ResponseComposer.Choice(EDIT_ID, "Alterar"));
        String summary = answer.checkout().summaryText();
        tenants.runInTenant(tenant, () -> record(tenant, conversation, "OUT", "BOT", summary, null));
        long at = HumanPace.plan(List.of(summary), config, Instant.EPOCH, 1).getLast().at().toEpochMilli();
        return new Reply(List.of(new Bubble(summary, at, buttons, true)), typing(List.of(summary), config),
                "CHECKOUT", answer.toolsCalled());
    }

    private Reply answer(TenantId tenant, UUID conversation, String text, List<String> options, String outcome,
                         List<String> tools, JsonNode config) {
        List<ResponseComposer.Bubble> composed = ResponseComposer.compose(text, options, config);
        List<String> texts = composed.stream().map(ResponseComposer.Bubble::text).toList();
        tenants.runInTenant(tenant, () -> {
            record(tenant, conversation, "OUT", "BOT", String.join("\n\n", texts), null);
            jdbc.sql("UPDATE conversation SET context = context - 'simulatorCheckout' WHERE id = :id")
                    .param("id", conversation).update();
        });
        List<HumanPace.Scheduled> plan = HumanPace.plan(texts, config, Instant.EPOCH, 1);
        List<Bubble> bubbles = new ArrayList<>();
        for (HumanPace.Scheduled step : plan) {
            if (step.step() == HumanPace.Step.BUBBLE) {
                ResponseComposer.Bubble bubble = composed.get(step.bubble());
                bubbles.add(bubble instanceof ResponseComposer.ChoiceBubble choice
                        ? new Bubble(choice.text(), step.at().toEpochMilli(), choice.choices(), choice.asButtons())
                        : new Bubble(bubble.text(), step.at().toEpochMilli(), List.of(), false));
            }
        }
        return new Reply(bubbles, typing(texts, config), outcome, tools);
    }

    private static List<Long> typing(List<String> texts, JsonNode config) {
        return HumanPace.plan(texts, config, Instant.EPOCH, 1).stream()
                .filter(step -> step.step() == HumanPace.Step.TYPING).map(step -> step.at().toEpochMilli()).toList();
    }

    /** The simulated summary is the last thing the bot said. */
    private boolean awaitingConfirmation(UUID conversation) {
        return jdbc.sql("""
                        SELECT (c.context ->> 'simulatorCheckout') = 'true'
                               AND (SELECT m.author FROM message m WHERE m.conversation_id = c.id AND m.direction = 'OUT'
                                    ORDER BY m.created_at DESC LIMIT 1) = 'BOT'
                        FROM conversation c WHERE c.id = :id
                        """)
                .param("id", conversation).query(Boolean.class).optional().orElse(false);
    }

    private UUID conversationOf(TenantId tenant, UUID staff) {
        UUID contact = jdbc.sql("""
                        INSERT INTO contact (tenant_id, wa_id, display_name) VALUES (:tenant, :waId, 'Simulador')
                        ON CONFLICT (tenant_id, wa_id) DO UPDATE SET display_name = excluded.display_name
                        RETURNING id
                        """)
                .param("tenant", tenant.value()).param("waId", waId(staff)).query(UUID.class).single();
        return jdbc.sql("""
                        INSERT INTO conversation (tenant_id, contact_id, phone_number_id, simulated)
                        VALUES (:tenant, :contact, :phone, true)
                        ON CONFLICT (tenant_id, contact_id, phone_number_id) DO UPDATE SET updated_at = now()
                        RETURNING id
                        """)
                .param("tenant", tenant.value()).param("contact", contact).param("phone", PHONE_NUMBER_ID)
                .query(UUID.class).single();
    }

    private void record(TenantId tenant, UUID conversation, String direction, String author, String text,
                        String replyId) {
        jdbc.sql("""
                        INSERT INTO message (tenant_id, conversation_id, ref, direction, author, wamid, type, text,
                                             reply_id, sent_at)
                        VALUES (:tenant, :conversation, :ref, :direction, :author, :wamid, 'text', :text, :replyId, :at)
                        """)
                .param("tenant", tenant.value()).param("conversation", conversation)
                .param("ref", "sim:" + UUID.randomUUID()).param("direction", direction).param("author", author)
                .param("wamid", "IN".equals(direction) ? "sim." + UUID.randomUUID() : null)
                .param("text", text).param("replyId", replyId)
                .param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update();
    }

    /** Simulated customers have no WhatsApp number: one per staff member. */
    private static String waId(UUID staff) {
        return "simulator:" + staff;
    }
}
