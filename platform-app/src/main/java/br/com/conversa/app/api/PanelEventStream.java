package br.com.conversa.app.api;

import br.com.conversa.core.tenant.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Open SSE connections of the panel, per tenant, in this process (one instance on the VPS,
 * ADR-004). Events go out after their transaction commits, so the panel never fetches an order
 * that is not there yet; a heartbeat keeps proxies from closing idle connections.
 */
@Component
public class PanelEventStream {

    private static final Logger log = LoggerFactory.getLogger(PanelEventStream.class);
    private static final Duration TIMEOUT = Duration.ofMinutes(30);

    private final Map<TenantId, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    SseEmitter open(TenantId tenant) {
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        List<SseEmitter> list = emitters.computeIfAbsent(tenant, key -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        emitter.onCompletion(() -> list.remove(emitter));
        emitter.onTimeout(() -> list.remove(emitter));
        emitter.onError(error -> list.remove(emitter));
        send(emitter, SseEmitter.event().name("ready").data("{}"));
        return emitter;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void publish(PanelEvent event) {
        String data = "{\"type\":\"" + event.type() + "\",\"id\":\"" + event.id() + "\"}";
        for (SseEmitter emitter : emitters.getOrDefault(event.tenant(), List.of())) {
            send(emitter, SseEmitter.event().name(event.type()).data(data));
        }
    }

    @Scheduled(fixedDelay = 25_000)
    void heartbeat() {
        emitters.values().forEach(list -> list.forEach(emitter -> send(emitter, SseEmitter.event().comment("ping"))));
    }

    int connections(TenantId tenant) {
        return emitters.getOrDefault(tenant, List.of()).size();
    }

    private static void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            log.debug("Panel connection closed: {}", e.getMessage());
            emitter.completeWithError(e);
        }
    }
}
