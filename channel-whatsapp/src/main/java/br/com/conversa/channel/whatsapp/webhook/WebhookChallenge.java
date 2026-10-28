package br.com.conversa.channel.whatsapp.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * Verificação do webhook (GET) feita pela Meta ao cadastrar a URL de callback.
 * Devolve o {@code hub.challenge} somente se o modo for {@code subscribe} e o token conferir.
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
