package com.lineaibot.line;

import com.lineaibot.config.AppProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
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
        this.restClient = restClientBuilder.build();
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
        String outboxId = repository.insertOutbox(
                tenantId, lineUserId, replyToken, payload, Instant.now());
        if (!properties.isLineApiEnabled()) {
            repository.markOutboxSent(outboxId, "SIMULATED", Instant.now());
            return;
        }
        try {
            restClient.post()
                    .uri(properties.getLineApiBaseUrl().replaceAll("/+$", "")
                            + "/v2/bot/message/reply")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + channelAccessToken)
                    .body(Map.of("replyToken", replyToken, "messages", messages))
                    .retrieve()
                    .toBodilessEntity();
            repository.markOutboxSent(outboxId, "SENT", Instant.now());
        } catch (RestClientException exception) {
            repository.markOutboxFailed(outboxId, exception.getMessage());
            throw new IllegalStateException("LINE reply request failed", exception);
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
