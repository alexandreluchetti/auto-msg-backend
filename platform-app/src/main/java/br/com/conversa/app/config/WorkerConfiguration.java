package br.com.conversa.app.config;

import br.com.conversa.app.WorkerProperties;
import br.com.conversa.app.conversation.ReplyWorker;
import br.com.conversa.app.inbound.InboundWorker;
import br.com.conversa.app.outbound.SenderWorker;
import br.com.conversa.app.privacy.PrivacyService;
import br.com.conversa.app.privacy.RetentionJob;
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
        private final ReplyWorker replies;
        private final RetentionJob retention;
        private final PrivacyService privacy;

        Schedule(InboundWorker inbound, SenderWorker sender, ReplyWorker replies, RetentionJob retention,
                 PrivacyService privacy) {
            this.inbound = inbound;
            this.sender = sender;
            this.replies = replies;
            this.retention = retention;
            this.privacy = privacy;
        }

        @Scheduled(fixedDelayString = "${conversa.workers.poll-interval:500ms}")
        void pollInbound() {
            inbound.runOnce();
        }

        @Scheduled(fixedDelayString = "${conversa.workers.poll-interval:500ms}")
        void pollOutbound() {
            sender.runOnce();
        }

        @Scheduled(fixedDelayString = "${conversa.workers.poll-interval:500ms}")
        void pollReplies() {
            replies.runOnce();
        }

        /** Second phase of erasures, shortly after the confirmation goes out. */
        @Scheduled(fixedDelay = 60_000)
        void purgeErasedQueues() {
            privacy.purgeQueues();
        }

        @Scheduled(cron = "${conversa.retention.cron:0 30 3 * * *}", zone = "America/Sao_Paulo")
        void retention() {
            retention.runOnce();
        }
    }
}
