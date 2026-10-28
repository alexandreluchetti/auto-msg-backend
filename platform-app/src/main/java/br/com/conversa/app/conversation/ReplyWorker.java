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
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

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
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Clock clock;

    ReplyWorker(ConversationRepository conversations, AssistantEngine engine, TenantTransactions tenants,
                TenantService tenantService, BusinessDirectory directory, OutboundMessageRepository outbound,
                AssistantProperties properties, AnthropicProperties anthropic, TransactionTemplate transactions,
                ObjectMapper mapper, Clock clock) {
        this.conversations = conversations;
        this.engine = engine;
        this.tenants = tenants;
        this.tenantService = tenantService;
        this.directory = directory;
        this.outbound = outbound;
        this.properties = properties;
        this.anthropic = anthropic;
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
            var answer = engine.answer(new AssistantEngine.Turn(tenant, claim.conversationId(),
                    tenantService.packOf(tenant), tenantService.effectiveConfig(tenant).toString(),
                    directory.openingHours(tenant).zone(), history, anthropic.defaultModel(),
                    claim.attempts() >= properties.maxAttempts()));
            String ref = "reply:" + claim.conversationId() + ":" + lastWamid;
            tenants.runInTenant(tenant, () -> {
                if (conversations.recordBotMessage(tenant, claim.conversationId(), ref, answer.text(), clock.instant())) {
                    outbound.enqueue("read:" + lastWamid, tenant, OutboundRequest.readReceipt(
                            conversation.phoneNumberId(), conversation.waId(), lastWamid, mapper));
                    outbound.enqueue(ref, tenant, OutboundRequest.text(
                            conversation.phoneNumberId(), conversation.waId(), answer.text(), mapper));
                }
                conversations.finish(claim);
            });
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
}
