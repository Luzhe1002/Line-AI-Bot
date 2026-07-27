package com.lineaibot.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class CryptoServiceTest {

    private final CryptoService crypto = new CryptoService();

    @Test
    void apiKeysAndEncryptedSecretsRoundTripWithoutStoringPlaintext() {
        String apiKey = crypto.generateApiKey();
        String hash = crypto.hashApiKey(apiKey);
        String encrypted = crypto.encryptSecret("application-encryption-key", "line-secret");

        assertThat(hash).doesNotContain(apiKey);
        assertThat(crypto.verifyApiKey(apiKey, hash)).isTrue();
        assertThat(crypto.verifyApiKey(apiKey + "-wrong", hash)).isFalse();
        assertThat(encrypted).doesNotContain("line-secret");
        assertThat(crypto.decryptSecret("application-encryption-key", encrypted))
                .isEqualTo("line-secret");
    }

    @Test
    void lineSignatureUsesTheUntouchedRequestBytes() throws Exception {
        byte[] body = "{\"events\":[]}\n".getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "channel-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getEncoder().encodeToString(mac.doFinal(body));

        assertThat(crypto.verifyLineSignature(body, signature, "channel-secret")).isTrue();
        assertThat(crypto.verifyLineSignature(
                        "{\"events\":[]}".getBytes(StandardCharsets.UTF_8),
                        signature,
                        "channel-secret"))
                .isFalse();
    }
}
