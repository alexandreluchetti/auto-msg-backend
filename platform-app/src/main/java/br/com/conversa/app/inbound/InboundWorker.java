package br.com.conversa.app.inbound;

import br.com.conversa.app.WorkerProperties;
import br.com.conversa.app.inbound.InboundEventQueue.ClaimedInbound;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Processes {@code inbound_event} one event per transaction, in arrival order. A single worker
 * keeps the order of each conversation; per-conversation advisory locks come with parallel
 * workers (ADR-004).
 */
@Component
public class InboundWorker {

    private static final Logger log = LoggerFactory.getLogger(InboundWorker.class);

    private final InboundEventQueue queue;
    private final InboundEventProcessor processor;
    private final TransactionTemplate transactions;
    private final WorkerProperties properties;

    InboundWorker(InboundEventQueue queue, InboundEventProcessor processor, TransactionTemplate transactions,
                  WorkerProperties properties) {
        this.queue = queue;
        this.processor = processor;
        this.transactions = transactions;
        this.properties = properties;
    }

    /** @return how many events were handled, successfully or not */
    public int runOnce() {
        int handled = 0;
        while (handled < properties.batchSize() && processNext()) {
            handled++;
        }
        return handled;
    }

    private boolean processNext() {
        AtomicReference<ClaimedInbound> current = new AtomicReference<>();
        try {
            return Boolean.TRUE.equals(transactions.execute(status -> {
                Optional<ClaimedInbound> next = queue.claimNext();
                next.ifPresent(event -> {
                    current.set(event);
                    processor.process(event);
                    queue.markProcessed(event.id());
                });
                return next.isPresent();
            }));
        } catch (RuntimeException e) {
            ClaimedInbound event = current.get();
            if (event == null) {
                throw e;
            }
            var delay = properties.retryDelay(event.attempts() + 1, ThreadLocalRandom.current().nextDouble());
            transactions.executeWithoutResult(status ->
                    queue.recordFailure(event.id(), properties.maxAttempts(), delay, e.getClass().getSimpleName()
                            + ": " + e.getMessage()));
            log.warn("Inbound event {} ({}) failed, attempt {}", event.id(), event.type(), event.attempts() + 1, e);
            return true;
        }
    }
}
