package br.com.conversa.app.conversation;

import br.com.conversa.app.conversation.ConversationRepository.ClaimedReply;
import br.com.conversa.app.conversation.ConversationRepository.ConversationView;
import br.com.conversa.app.conversation.ConversationRepository.StoredMessage;
import br.com.conversa.app.outbound.OutboundMessageRepository;
import br.com.conversa.app.outbound.OutboundRequest;
import br.com.conversa.app.tenant.TenantService;
import br.com.conversa.app.tenant.TenantTransactions;
import br.com.conversa.core.business.BusinessDirectory;
import br.com.conversa.core.llm.LlmException;
import br.com.conversa.core.tenant.TenantId;
import br.com.conversa.llm.anthropic.AnthropicProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Answers conversations whose reply is due (after the debounce): claims one with a lease, builds
 * the answer with {@link AssistantEngine} outside any transaction, then stores it and enqueues the
 * read receipt and the text in one transaction of the tenant. Answers are idempotent per last
 * customer message, so a crash after enqueueing never sends twice.
 */
@Component
public class ReplyWorker {

    private static final Logger log = LoggerFactory.getLogger(ReplyWorker.class);
    private static final int BATCH = 20;

    private final ConversationRepository conversations;
    private final AssistantEngine engine;
    private final TenantTransactions tenants;
    private final TenantService tenantService;
    private final BusinessDirectory directory;
    private final OutboundMessageRepository outbound;
    private final AssistantProperties properties;
    private final AnthropicProperties anthropic;
    private final HandoffService handoff;
    private final HandoffProperties handoffProperties;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Clock clock;

    ReplyWorker(ConversationRepository conversations, AssistantEngine engine, TenantTransactions tenants,
                TenantService tenantService, BusinessDirectory directory, OutboundMessageRepository outbound,
                AssistantProperties properties, AnthropicProperties anthropic, HandoffService handoff,
                HandoffProperties handoffProperties, TransactionTemplate transactions,
                ObjectMapper mapper, Clock clock) {
        this.conversations = conversations;
        this.engine = engine;
        this.tenants = tenants;
        this.tenantService = tenantService;
        this.directory = directory;
        this.outbound = outbound;
        this.properties = properties;
        this.anthropic = anthropic;
        this.handoff = handoff;
        this.handoffProperties = handoffProperties;
        this.transactions = transactions;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Answers up to 20 due conversations. @return how many were handled */
    public int runOnce() {
        int handled = 0;
        Optional<ClaimedReply> claim;
        while (handled < BATCH
                && (claim = transactions.execute(status -> conversations.claimDue(properties.replyLease()))).isPresent()) {
            handle(claim.get());
            handled++;
        }
        return handled;
    }

    private void handle(ClaimedReply claim) {
        TenantId tenant = claim.tenant();
        try {
            ConversationView conversation = tenants.inTenant(tenant, () -> conversations.find(claim.conversationId()))
                    .orElse(null);
            List<StoredMessage> history = tenants.inTenant(tenant,
                    () -> conversations.recentMessages(claim.conversationId(), properties.historyMessages()));
            if (conversation == null || !"BOT".equals(conversation.state())
                    || history.isEmpty() || !history.getLast().fromCustomer()) {
                transactions.executeWithoutResult(status -> conversations.finish(claim));
                return;
            }
            String lastWamid = history.getLast().wamid();
            JsonNode config = tenantService.effectiveConfig(tenant);
            if (HandoffService.asksForPerson(history.getLast().text())) {
                deliver(claim, lastWamid, current -> handoff.handOver(tenant, current, lastWamid,
                        HandoffReason.CUSTOMER_REQUEST, null, config));
                return;
            }
            var answer = engine.answer(new AssistantEngine.Turn(tenant, claim.conversationId(),
                    tenantService.packOf(tenant), config.toString(),
                    directory.openingHours(tenant).zone(), history, anthropic.defaultModel(),
                    claim.attempts() >= properties.maxAttempts()));
            int failures = answer.outcome() == AssistantEngine.Outcome.FALLBACK ? conversation.failureStreak() + 1 : 0;
            if (answer.outcome() == AssistantEngine.Outcome.HANDOFF || failures >= handoffProperties.failureThreshold()) {
                HandoffReason reason = answer.outcome() == AssistantEngine.Outcome.HANDOFF
                        ? HandoffReason.BOT_REQUEST : HandoffReason.REPEATED_FAILURES;
                deliver(claim, lastWamid, current -> handoff.handOver(tenant, current, lastWamid, reason,
                        answer.handoffDetail(), config));
            } else {
                String ref = "reply:" + claim.conversationId() + ":" + lastWamid;
                deliver(claim, lastWamid, current -> {
                    if (conversations.recordBotMessage(tenant, current.id(), ref, answer.text(), clock.instant())) {
                        outbound.enqueue("read:" + lastWamid, tenant, OutboundRequest.readReceipt(
                                current.phoneNumberId(), current.waId(), lastWamid, mapper));
                        outbound.enqueue(ref, tenant, OutboundRequest.text(
                                current.phoneNumberId(), current.waId(), answer.text(), mapper));
                    }
                    conversations.setFailureStreak(current.id(), failures);
                });
            }
            log.info("Answered conversation {} ({}, tools {})", claim.conversationId(), answer.outcome(),
                    answer.toolsCalled());
        } catch (LlmException e) {
            log.warn("Temporary LLM failure in conversation {}, attempt {}: {}", claim.conversationId(),
                    claim.attempts(), e.getMessage());
            transactions.executeWithoutResult(status ->
                    conversations.retryLater(claim, properties.retryDelay(), e.getMessage()));
        } catch (RuntimeException e) {
            log.error("Cannot answer conversation {}", claim.conversationId(), e);
            transactions.executeWithoutResult(status -> {
                if (claim.attempts() >= properties.maxAttempts()) {
                    conversations.finish(claim);
                } else {
                    conversations.retryLater(claim, properties.retryDelay(), String.valueOf(e.getMessage()));
                }
            });
        }
    }

    /**
     * Runs {@code effect} in one transaction of the tenant, only if the bot is still in charge and no
     * newer customer message arrived while the answer was built (then the next answer covers it).
     */
    private void deliver(ClaimedReply claim, String lastWamid, Consumer<ConversationView> effect) {
        tenants.runInTenant(claim.tenant(), () -> {
            ConversationView current = conversations.findForUpdate(claim.conversationId()).orElse(null);
            List<StoredMessage> latest = conversations.recentMessages(claim.conversationId(), 1);
            boolean stillDue = current != null && "BOT".equals(current.state())
                    && !latest.isEmpty() && lastWamid.equals(latest.getLast().wamid());
            if (stillDue) {
                effect.accept(current);
            }
            conversations.finish(claim);
        });
    }
}
