package com.lineaibot.line;

import com.lineaibot.config.AppProperties;
import com.lineaibot.shared.ApiException;
import com.lineaibot.shared.CryptoService;
import com.lineaibot.tenant.TenantRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/webhooks/line")
public class LineWebhookController {

    private static final Logger log = LoggerFactory.getLogger(LineWebhookController.class);

    public record WebhookAccepted(int accepted, int duplicate) {}

    private final TenantRepository tenants;
    private final LineRepository events;
    private final CryptoService crypto;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public LineWebhookController(
            TenantRepository tenants,
            LineRepository events,
            CryptoService crypto,
            AppProperties properties,
            ObjectMapper objectMapper) {
        this.tenants = tenants;
        this.events = events;
        this.crypto = crypto;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{tenantSlug}")
    WebhookAccepted webhook(
            @PathVariable String tenantSlug,
            @RequestHeader(name = "X-Line-Signature", required = false) String signature,
            HttpServletRequest request) throws IOException {
        var tenant = tenants.findActiveBySlug(tenantSlug)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Tenant not found"));
        var channel = tenants.findLineChannel(tenant.id())
                .filter(TenantRepository.LineChannelRow::enabled)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "LINE channel not found"));
        byte[] rawBody = request.getInputStream().readAllBytes();
        if (signature == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Missing LINE signature");
        }
        String channelSecret = crypto.decryptSecret(
                properties.getEncryptionKey(), channel.channelSecretEncrypted());
        if (!crypto.verifyLineSignature(rawBody, signature, channelSecret)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid LINE signature");
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(rawBody);
        } catch (JacksonException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid JSON");
        }
        JsonNode eventNodes = payload.path("events");
        if (!eventNodes.isArray()) {
            return new WebhookAccepted(0, 0);
        }

        int accepted = 0;
        int duplicate = 0;
        for (int index = 0; index < eventNodes.size(); index++) {
            JsonNode event = eventNodes.get(index);
            String eventId = event.path("webhookEventId").asText("");
            if (eventId.isBlank()) {
                eventId = fingerprint(rawBody, index);
            }
            try {
                events.insertEvent(
                        UUID.randomUUID().toString(),
                        tenant.id(),
                        eventId,
                        event.path("type").asText("unknown"),
                        event.path("source").path("userId").asText(null),
                        objectMapper.writeValueAsString(event),
                        Instant.now());
                accepted++;
            } catch (DataIntegrityViolationException exception) {
                duplicate++;
            }
        }
        log.info(
                "LINE webhook accepted tenantId={} eventCount={} accepted={} duplicate={}",
                tenant.id(),
                eventNodes.size(),
                accepted,
                duplicate);
        return new WebhookAccepted(accepted, duplicate);
    }

    private String fingerprint(byte[] rawBody, int index) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(rawBody);
            digest.update((byte) ':');
            digest.update(Integer.toString(index).getBytes(StandardCharsets.US_ASCII));
            return java.util.HexFormat.of().formatHex(digest.digest()).substring(0, 32);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
