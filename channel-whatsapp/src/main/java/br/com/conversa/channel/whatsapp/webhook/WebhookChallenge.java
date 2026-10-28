package br.com.conversa.channel.whatsapp.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * Webhook verification (GET) Meta performs when the callback URL is registered.
 * Returns {@code hub.challenge} only if the mode is {@code subscribe} and the token matches.
 */
public final class WebhookChallenge {

    private WebhookChallenge() {
    }

    public static Optional<String> verify(String mode, String token, String challenge, String expectedToken) {
        if (expectedToken == null || expectedToken.isBlank() || token == null || challenge == null) {
            return Optional.empty();
        }
        boolean tokenMatches = MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), expectedToken.getBytes(StandardCharsets.UTF_8));
        return "subscribe".equals(mode) && tokenMatches ? Optional.of(challenge) : Optional.empty();
    }
}
