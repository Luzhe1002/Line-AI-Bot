package com.lineaibot.line;

import com.lineaibot.config.AppProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class LineMessagingClient {

    private final LineRepository repository;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public LineMessagingClient(
            LineRepository repository,
            AppProperties properties,
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder) {
        this.repository = repository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(20));
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
    }

    public void reply(
            String tenantId,
            String channelAccessToken,
            String replyToken,
            String lineUserId,
            List<Map<String, Object>> messages) {
        if (messages.isEmpty() || messages.size() > 5) {
            throw new IllegalArgumentException(
                    "LINE reply must contain between one and five messages");
        }
        String payload = toJson(messages);
        deliver(
                tenantId,
                channelAccessToken,
                replyToken,
                lineUserId,
                "REPLY",
                messages,
                payload);
    }

    public boolean pushFailedReplyIfPresent(
            String tenantId,
            String channelAccessToken,
            String replyToken,
            String lineUserId) {
        return repository.findLatestFailedReplyPayload(tenantId, lineUserId, replyToken)
                .map(payload -> {
                    try {
                        deliver(
                                tenantId,
                                channelAccessToken,
                                null,
                                lineUserId,
                                "PUSH",
                                objectMapper.readTree(payload),
                                payload);
                        return true;
                    } catch (JacksonException exception) {
                        throw new IllegalStateException(
                                "Unable to deserialize failed LINE reply", exception);
                    }
                })
                .orElse(false);
    }

    public void push(
            String tenantId,
            String channelAccessToken,
            String lineUserId,
            List<Map<String, Object>> messages,
            String dedupeKey) {
        if (messages.isEmpty() || messages.size() > 5) {
            throw new IllegalArgumentException(
                    "LINE push must contain between one and five messages");
        }
        deliver(
                tenantId,
                channelAccessToken,
                null,
                lineUserId,
                "PUSH",
                messages,
                toJson(messages),
                dedupeKey);
    }

    private void deliver(
            String tenantId,
            String channelAccessToken,
            String replyToken,
            String lineUserId,
            String deliveryType,
            Object messages,
            String payload) {
        deliver(
                tenantId,
                channelAccessToken,
                replyToken,
                lineUserId,
                deliveryType,
                messages,
                payload,
                null);
    }

    private void deliver(
            String tenantId,
            String channelAccessToken,
            String replyToken,
            String lineUserId,
            String deliveryType,
            Object messages,
            String payload,
            String dedupeKey) {
        var existing = repository.findOutboxByDedupeKey(dedupeKey);
        if (existing.isPresent()
                && ("SENT".equals(existing.get().status())
                        || "SIMULATED".equals(existing.get().status()))) {
            return;
        }
        Object deliveryMessages = messages;
        if (existing.isPresent()) {
            try {
                deliveryMessages = objectMapper.readTree(existing.get().payloadJson());
            } catch (JacksonException exception) {
                throw new IllegalStateException(
                        "Unable to deserialize deduplicated LINE payload", exception);
            }
        }
        String outboxId = existing.map(LineRepository.OutboxDeliveryRow::id)
                .orElseGet(() -> repository.insertOutbox(
                        tenantId,
                        lineUserId,
                        replyToken,
                        deliveryType,
                        payload,
                        dedupeKey,
                        Instant.now()));
        if (!properties.isLineApiEnabled()) {
            repository.markOutboxSent(outboxId, "SIMULATED", Instant.now());
            return;
        }
        try {
            boolean push = "PUSH".equals(deliveryType);
            restClient.post()
                    .uri(properties.getLineApiBaseUrl().replaceAll("/+$", "")
                            + (push
                                    ? "/v2/bot/message/push"
                                    : "/v2/bot/message/reply"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + channelAccessToken)
                    .body(push
                            ? Map.of("to", lineUserId, "messages", deliveryMessages)
                            : Map.of("replyToken", replyToken, "messages", deliveryMessages))
                    .retrieve()
                    .toBodilessEntity();
            repository.markOutboxSent(outboxId, "SENT", Instant.now());
        } catch (RestClientException exception) {
            repository.markOutboxFailed(outboxId, exception.getMessage());
            throw new IllegalStateException(
                    "LINE " + deliveryType.toLowerCase() + " request failed", exception);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize LINE message", exception);
        }
    }
}
