package com.lineaibot.booking;

import com.lineaibot.config.AppProperties;
import com.lineaibot.shared.ApiException;
import com.lineaibot.shared.CryptoService;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class BookingAccessTokenService {

    private static final long TOKEN_TTL_SECONDS = 30 * 60;
    private static final String TOKEN_PURPOSE = "booking-access";

    public record BookingIdentity(String tenantId, String tenantSlug, String lineUserId) {}

    private final CryptoService crypto;
    private final AppProperties properties;

    public BookingAccessTokenService(CryptoService crypto, AppProperties properties) {
        this.crypto = crypto;
        this.properties = properties;
    }

    public String issue(String tenantId, String tenantSlug, String lineUserId) {
        long expiresAt = Instant.now().plusSeconds(TOKEN_TTL_SECONDS).getEpochSecond();
        String payload = String.join("\n", tenantId, tenantSlug, lineUserId, Long.toString(expiresAt));
        return crypto.encryptSecret(keyMaterial(), payload);
    }

    public BookingIdentity require(String tenantSlug, String token) {
        if (token == null || token.isBlank()) {
            throw unauthorized();
        }
        try {
            String[] parts = crypto.decryptSecret(keyMaterial(), token).split("\n", -1);
            if (parts.length != 4
                    || parts[0].isBlank()
                    || !tenantSlug.equals(parts[1])
                    || parts[2].isBlank()
                    || Instant.now().getEpochSecond() > Long.parseLong(parts[3])) {
                throw unauthorized();
            }
            return new BookingIdentity(parts[0], parts[1], parts[2]);
        } catch (IllegalArgumentException exception) {
            throw unauthorized();
        }
    }

    private String keyMaterial() {
        return properties.getEncryptionKey() + ":" + TOKEN_PURPOSE;
    }

    private ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "Booking link is invalid or expired");
    }
}
