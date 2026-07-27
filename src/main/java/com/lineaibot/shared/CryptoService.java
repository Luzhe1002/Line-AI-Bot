package com.lineaibot.shared;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class CryptoService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int API_KEY_ITERATIONS = 310_000;

    public String generateApiKey() {
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public String hashApiKey(String apiKey) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        byte[] digest = pbkdf2(apiKey, salt, API_KEY_ITERATIONS);
        return String.join(
                "$",
                "pbkdf2_sha256",
                Integer.toString(API_KEY_ITERATIONS),
                Base64.getUrlEncoder().encodeToString(salt),
                Base64.getUrlEncoder().encodeToString(digest));
    }

    public boolean verifyApiKey(String apiKey, String encodedHash) {
        if (apiKey == null || encodedHash == null) {
            return false;
        }
        try {
            String[] parts = encodedHash.split("\\$", 4);
            if (parts.length != 4 || !"pbkdf2_sha256".equals(parts[0])) {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getUrlDecoder().decode(parts[2]);
            byte[] expected = Base64.getUrlDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, pbkdf2(apiKey, salt, iterations));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public boolean constantTimeEquals(String first, String second) {
        if (first == null || second == null) {
            return false;
        }
        return MessageDigest.isEqual(
                first.getBytes(StandardCharsets.UTF_8),
                second.getBytes(StandardCharsets.UTF_8));
    }

    public boolean verifyLineSignature(byte[] rawBody, String signature, String channelSecret) {
        if (signature == null || channelSecret == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    channelSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = Base64.getEncoder().encodeToString(mac.doFinal(rawBody));
            return constantTimeEquals(expected, signature);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to verify LINE signature", exception);
        }
    }

    public String encryptSecret(String keyMaterial, String plaintext) {
        try {
            byte[] fernetKey = sha256(keyMaterial);
            byte[] signingKey = Arrays.copyOfRange(fernetKey, 0, 16);
            byte[] encryptionKey = Arrays.copyOfRange(fernetKey, 16, 32);
            byte[] iv = new byte[16];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(encryptionKey, "AES"),
                    new IvParameterSpec(iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer signed = ByteBuffer.allocate(1 + 8 + iv.length + ciphertext.length);
            signed.put((byte) 0x80);
            signed.putLong(Instant.now().getEpochSecond());
            signed.put(iv);
            signed.put(ciphertext);
            byte[] signedBytes = signed.array();

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            byte[] token = ByteBuffer.allocate(signedBytes.length + 32)
                    .put(signedBytes)
                    .put(mac.doFinal(signedBytes))
                    .array();
            return Base64.getUrlEncoder().encodeToString(token);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt secret", exception);
        }
    }

    public String decryptSecret(String keyMaterial, String tokenValue) {
        try {
            byte[] token = Base64.getUrlDecoder().decode(tokenValue);
            if (token.length < 1 + 8 + 16 + 16 + 32 || token[0] != (byte) 0x80) {
                throw new IllegalArgumentException("Invalid encrypted secret");
            }
            byte[] fernetKey = sha256(keyMaterial);
            byte[] signingKey = Arrays.copyOfRange(fernetKey, 0, 16);
            byte[] encryptionKey = Arrays.copyOfRange(fernetKey, 16, 32);
            byte[] signed = Arrays.copyOfRange(token, 0, token.length - 32);
            byte[] suppliedMac = Arrays.copyOfRange(token, token.length - 32, token.length);

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            if (!MessageDigest.isEqual(suppliedMac, mac.doFinal(signed))) {
                throw new IllegalArgumentException("Unable to decrypt stored secret");
            }

            byte[] iv = Arrays.copyOfRange(token, 9, 25);
            byte[] ciphertext = Arrays.copyOfRange(token, 25, token.length - 32);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(encryptionKey, "AES"),
                    new IvParameterSpec(iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unable to decrypt stored secret", exception);
        }
    }

    public String stableHmac(String keyMaterial, String subject) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    keyMaterial.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of()
                    .formatHex(mac.doFinal(subject.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to create stable identifier", exception);
        }
    }

    private byte[] pbkdf2(String value, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(value.toCharArray(), salt, iterations, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to hash API key", exception);
        }
    }

    private byte[] sha256(String value) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
    }
}
