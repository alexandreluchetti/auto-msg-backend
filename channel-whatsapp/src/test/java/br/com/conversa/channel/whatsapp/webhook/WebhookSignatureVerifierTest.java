package br.com.conversa.channel.whatsapp.webhook;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureVerifierTest {

    private static final String SECRET = "app-secret";
    private static final byte[] BODY = "{\"object\":\"whatsapp_business_account\"}".getBytes(StandardCharsets.UTF_8);

    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(SECRET);

    @Test
    void acceptsSignatureComputedWithAppSecret() {
        assertThat(verifier.isValid(BODY, sign(BODY, SECRET))).isTrue();
    }

    @Test
    void rejectsSignatureFromAnotherSecret() {
        assertThat(verifier.isValid(BODY, sign(BODY, "other-secret"))).isFalse();
    }

    @Test
    void rejectsTamperedBody() {
        byte[] tampered = "{\"object\":\"whatsapp_business_account\" }".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.isValid(tampered, sign(BODY, SECRET))).isFalse();
    }

    @Test
    void rejectsMissingOrMalformedHeader() {
        assertThat(verifier.isValid(BODY, null)).isFalse();
        assertThat(verifier.isValid(BODY, sign(BODY, SECRET).substring("sha256=".length()))).isFalse();
        assertThat(verifier.isValid(BODY, "sha256=not-hex")).isFalse();
    }

    @Test
    void failsClosedWithoutConfiguredSecret() {
        assertThat(new WebhookSignatureVerifier("").isValid(BODY, sign(BODY, "x"))).isFalse();
        assertThat(new WebhookSignatureVerifier(null).isValid(BODY, sign(BODY, SECRET))).isFalse();
    }

    static String sign(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
