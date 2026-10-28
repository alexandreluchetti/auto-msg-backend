package br.com.conversa.channel.whatsapp.webhook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookChallengeTest {

    @Test
    void returnsChallengeWhenModeAndTokenMatch() {
        assertThat(WebhookChallenge.verify("subscribe", "tok", "12345", "tok")).contains("12345");
    }

    @Test
    void rejectsWrongTokenOrMode() {
        assertThat(WebhookChallenge.verify("subscribe", "wrong", "12345", "tok")).isEmpty();
        assertThat(WebhookChallenge.verify("unsubscribe", "tok", "12345", "tok")).isEmpty();
    }

    @Test
    void rejectsWhenNoTokenIsConfigured() {
        assertThat(WebhookChallenge.verify("subscribe", "", "12345", "")).isEmpty();
        assertThat(WebhookChallenge.verify("subscribe", "tok", "12345", null)).isEmpty();
    }
}
