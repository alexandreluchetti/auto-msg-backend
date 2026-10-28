package br.com.conversa.channel.whatsapp.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Valida o header {@code X-Hub-Signature-256}: HMAC-SHA256 do corpo cru com o app secret.
 * Falha fechada: sem segredo configurado, nenhuma assinatura é aceita. Ver ADR-001.
 */
public final class WebhookSignatureVerifier {

    private static final String PREFIX = "sha256=";
    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] appSecret;

    public WebhookSignatureVerifier(String appSecret) {
        this.appSecret = appSecret == null ? new byte[0] : appSecret.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isValid(byte[] rawBody, String signatureHeader) {
        if (appSecret.length == 0 || rawBody == null || signatureHeader == null
                || !signatureHeader.startsWith(PREFIX)) {
            return false;
        }
        byte[] received;
        try {
            received = HexFormat.of().parseHex(signatureHeader.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(hmac(rawBody), received);
    }

    private byte[] hmac(byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(appSecret, ALGORITHM));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 indisponível", e);
        }
    }
}
