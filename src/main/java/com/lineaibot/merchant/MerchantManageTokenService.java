package com.lineaibot.merchant;

import com.lineaibot.config.AppProperties;
import com.lineaibot.shared.ApiException;
import com.lineaibot.shared.CryptoService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantManageTokenService {

    private static final long TOKEN_TTL_MINUTES = 10;

    public record ManageIdentity(String tenantId, String staffId) {}

    private final MerchantRepository repository;
    private final CryptoService crypto;
    private final AppProperties properties;

    public MerchantManageTokenService(
            MerchantRepository repository,
            CryptoService crypto,
            AppProperties properties) {
        this.repository = repository;
        this.crypto = crypto;
        this.properties = properties;
    }

    public String issue(String tenantId, String staffId) {
        String secret = crypto.generateApiKey();
        Instant now = Instant.now();
        repository.insertManageToken(
                UUID.randomUUID().toString(),
                tenantId,
                staffId,
                tokenHash(secret),
                now.plus(TOKEN_TTL_MINUTES, ChronoUnit.MINUTES),
                now);
        return secret;
    }

    @Transactional
    public ManageIdentity consume(String token) {
        if (token == null || token.isBlank()) {
            throw unauthorized();
        }
        Instant now = Instant.now();
        var row = repository.findUsableManageToken(tokenHash(token), now)
                .orElseThrow(this::unauthorized);
        if (!repository.consumeManageToken(row.id(), now)) {
            throw unauthorized();
        }
        return new ManageIdentity(row.tenantId(), row.staffId());
    }

    private String tokenHash(String value) {
        return crypto.stableHmac(
                properties.getEncryptionKey() + ":merchant-manage-token", value);
    }

    private ApiException unauthorized() {
        return new ApiException(
                HttpStatus.UNAUTHORIZED, "Merchant management link is invalid or expired");
    }
}
