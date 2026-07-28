package com.lineaibot.line;

import com.lineaibot.config.AppProperties;
import com.lineaibot.shared.CryptoService;
import com.lineaibot.tenant.TenantRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class LineEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(LineEventProcessor.class);

    private final LineRepository lineRepository;
    private final TenantRepository tenantRepository;
    private final ConversationService conversation;
    private final LineMessagingClient lineClient;
    private final CryptoService crypto;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public LineEventProcessor(
            LineRepository lineRepository,
            TenantRepository tenantRepository,
            ConversationService conversation,
            LineMessagingClient lineClient,
            CryptoService crypto,
            AppProperties properties,
            ObjectMapper objectMapper) {
        this.lineRepository = lineRepository;
        this.tenantRepository = tenantRepository;
        this.conversation = conversation;
        this.lineClient = lineClient;
        this.crypto = crypto;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void process(String eventId) {
        var event = lineRepository.findEvent(eventId).orElse(null);
        if (event == null) {
            log.warn("LINE event disappeared before processing eventId={}", eventId);
            return;
        }
        try {
            var tenant = tenantRepository.findById(event.tenantId())
                    .filter(TenantRepository.TenantRow::active)
                    .orElseThrow(() -> new IllegalStateException("Tenant is unavailable"));
            var channel = tenantRepository.findLineChannel(tenant.id())
                    .filter(TenantRepository.LineChannelRow::enabled)
                    .orElseThrow(() -> new IllegalStateException("LINE channel is unavailable"));
            JsonNode payload = objectMapper.readTree(event.payloadJson());
            String lineUserId = payload.path("source").path("userId").asText("");
            String replyToken = payload.path("replyToken").asText("");
            if (lineUserId.isBlank() || replyToken.isBlank()) {
                lineRepository.markEventProcessed(eventId, Instant.now());
                log.info(
                        "LINE event ignored without reply context eventId={} tenantId={} type={}",
                        eventId,
                        event.tenantId(),
                        event.eventType());
                return;
            }

            String token = crypto.decryptSecret(
                    properties.getEncryptionKey(), channel.channelAccessTokenEncrypted());
            if (event.attempts() > 1
                    && lineClient.pushFailedReplyIfPresent(
                            tenant.id(), token, replyToken, lineUserId)) {
                lineRepository.markEventProcessed(eventId, Instant.now());
                return;
            }

            List<Map<String, Object>> messages;
            String eventType = payload.path("type").asText("");
            if ("message".equals(eventType)
                    && "text".equals(payload.path("message").path("type").asText(""))) {
                messages = conversation.handleText(
                        tenant,
                        lineUserId,
                        payload.path("message").path("text").asText(""));
            } else if ("postback".equals(eventType)) {
                messages = conversation.handlePostback(
                        tenant,
                        lineUserId,
                        payload.path("postback").path("data").asText(""),
                        event.webhookEventId());
            } else {
                messages = List.of(Map.of(
                        "type", "text",
                        "text", "目前僅支援文字訊息與預約操作。"));
            }

            lineClient.reply(
                    tenant.id(), token, replyToken, lineUserId, messages);
            lineRepository.markEventProcessed(eventId, Instant.now());
            log.info(
                    "LINE event processed eventId={} tenantId={} attempt={}",
                    eventId,
                    tenant.id(),
                    event.attempts());
        } catch (Exception exception) {
            log.warn(
                    "LINE event processing failed eventId={} tenantId={} attempt={} errorType={} message={}",
                    eventId,
                    event.tenantId(),
                    event.attempts(),
                    exception.getClass().getSimpleName(),
                    exception.getMessage());
            int delaySeconds = (int) Math.pow(2, Math.max(0, event.attempts() - 1));
            lineRepository.markEventRetryOrFailed(
                    eventId,
                    event.attempts(),
                    exception.getMessage() == null
                            ? exception.getClass().getSimpleName()
                            : exception.getMessage(),
                    Instant.now().plusSeconds(delaySeconds));
        }
    }
}
