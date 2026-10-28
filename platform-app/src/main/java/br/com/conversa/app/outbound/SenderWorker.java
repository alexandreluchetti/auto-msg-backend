package br.com.conversa.app.outbound;

import br.com.conversa.app.WorkerProperties;
import br.com.conversa.app.outbound.OutboundMessageRepository.ClaimedOutbound;
import br.com.conversa.core.channel.ChannelGateway;
import br.com.conversa.core.channel.SendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sends what the outbox holds. Free text outside the 24h window fails without calling the API,
 * since Meta would reject it (and templates are the only way to reach the customer then).
 */
@Component
public class SenderWorker {

    static final String WINDOW_CLOSED = "WINDOW_CLOSED: free text outside the 24h window; use a template";

    private static final Logger log = LoggerFactory.getLogger(SenderWorker.class);

    private final OutboundMessageRepository repository;
    private final CustomerServiceWindow window;
    private final ChannelGateway gateway;
    private final WorkerProperties properties;
    private final ObjectMapper mapper;

    public SenderWorker(OutboundMessageRepository repository, CustomerServiceWindow window,
                        ChannelGateway gateway, WorkerProperties properties, ObjectMapper mapper) {
        this.repository = repository;
        this.window = window;
        this.gateway = gateway;
        this.properties = properties;
        this.mapper = mapper;
    }

    /** @return how many rows were handled */
    public int runOnce() {
        List<ClaimedOutbound> claimed = repository.claimDue(properties.batchSize(), properties.sendLease());
        claimed.forEach(this::handle);
        return claimed.size();
    }

    private void handle(ClaimedOutbound row) {
        OutboundRequest request = row.request();
        if (request.kind() == OutboundKind.TEXT && !window.isOpen(request.phoneNumberId(), request.waId())) {
            repository.markFailed(row.id(), WINDOW_CLOSED);
            log.warn("Outbound {} {} not sent: window closed", row.id(), request.kind());
            return;
        }
        SendResult result = request.kind() == OutboundKind.READ
                ? gateway.markAsRead(request.phoneNumberId(), request.readMessageId(mapper))
                : gateway.send(request.phoneNumberId(), request.toMessage(mapper));
        switch (result) {
            case SendResult.Sent sent -> {
                repository.markSent(row.id(), sent.messageId());
                log.info("Outbound {} {} sent", row.id(), request.kind());
            }
            case SendResult.Failed failed when failed.retryable() && row.attempts() < properties.maxAttempts() -> {
                var delay = properties.retryDelay(row.attempts(), ThreadLocalRandom.current().nextDouble());
                repository.reschedule(row.id(), delay, describe(failed));
                log.warn("Outbound {} {} failed ({}), retry {} in {}", row.id(), request.kind(), failed.code(),
                        row.attempts() + 1, delay);
            }
            case SendResult.Failed failed -> {
                repository.markFailed(row.id(), describe(failed));
                log.warn("Outbound {} {} failed ({}) after {} attempt(s)", row.id(), request.kind(), failed.code(),
                        row.attempts());
            }
        }
    }

    private static String describe(SendResult.Failed failed) {
        return failed.code() + ": " + failed.detail();
    }
}
