package br.com.conversa.app.config;

import br.com.conversa.app.WorkerProperties;
import br.com.conversa.app.inbound.InboundWorker;
import br.com.conversa.app.outbound.SenderWorker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerProperties.class)
class WorkerConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Polls the queues. Off in tests, which call {@code runOnce()} themselves. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnBooleanProperty(name = "conversa.workers.enabled", matchIfMissing = true)
    static class Schedule {

        private final InboundWorker inbound;
        private final SenderWorker sender;

        Schedule(InboundWorker inbound, SenderWorker sender) {
            this.inbound = inbound;
            this.sender = sender;
        }

        @Scheduled(fixedDelayString = "${conversa.workers.poll-interval:500ms}")
        void pollInbound() {
            inbound.runOnce();
        }

        @Scheduled(fixedDelayString = "${conversa.workers.poll-interval:500ms}")
        void pollOutbound() {
            sender.runOnce();
        }
    }
}
