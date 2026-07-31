package com.lineaibot.line;

import com.lineaibot.config.AppProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class LineMessagingClient {

    private static final int MAX_ERROR_BODY_LENGTH = 500;

    private final LineRepository repository;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final RestClient restClient;

    public LineMessagingClient(
            LineRepository repository,
            AppProperties properties,
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder) {
        this.repository = repository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .version(HttpClient.Version.HTTP_1_1)
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

    public Optional<String> findRichMenuIdByName(
            String channelAccessToken, String name) {
        JsonNode response = restClient.get()
                .uri(apiBaseUrl() + "/v2/bot/richmenu/list")
                .header("Authorization", "Bearer " + channelAccessToken)
                .retrieve()
                .body(JsonNode.class);
        if (response == null) {
            return Optional.empty();
        }
        for (JsonNode menu : response.path("richmenus")) {
            if (name.equals(menu.path("name").asText())) {
                String id = menu.path("richMenuId").asText("");
                if (!id.isBlank()) {
                    return Optional.of(id);
                }
            }
        }
        return Optional.empty();
    }

    public String createRichMenu(
            String channelAccessToken, Map<String, Object> definition) {
        JsonNode response = restClient.post()
                .uri(apiBaseUrl() + "/v2/bot/richmenu")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + channelAccessToken)
                .body(definition)
                .retrieve()
                .body(JsonNode.class);
        String id = response == null ? "" : response.path("richMenuId").asText("");
        if (id.isBlank()) {
            throw new IllegalStateException("LINE rich menu creation returned no richMenuId");
        }
        return id;
    }

    public void uploadRichMenuImage(
            String channelAccessToken, String richMenuId, byte[] png) {
        var request = HttpRequest.newBuilder(URI.create(dataApiBaseUrl()
                        + "/v2/bot/richmenu/"
                        + richMenuId
                        + "/content"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + channelAccessToken)
                .header("Content-Type", MediaType.IMAGE_PNG_VALUE)
                .POST(HttpRequest.BodyPublishers.ofByteArray(png))
                .build();
        try {
            var response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String responseBody = sanitizeErrorBody(response.body());
                throw new RestClientResponseException(
                        "LINE rich menu image upload returned HTTP "
                                + response.statusCode()
                                + (responseBody.isBlank() ? "" : ": " + responseBody),
                        response.statusCode(),
                        "",
                        HttpHeaders.EMPTY,
                        response.body(),
                        StandardCharsets.UTF_8);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RestClientException(
                    "LINE rich menu image upload was interrupted", exception);
        } catch (IOException exception) {
            throw new RestClientException(
                    "LINE rich menu image upload failed", exception);
        }
    }

    private String sanitizeErrorBody(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        String text = new String(body, StandardCharsets.UTF_8)
                .replaceAll("[\\r\\n\\t]+", " ")
                .trim();
        return text.length() <= MAX_ERROR_BODY_LENGTH
                ? text
                : text.substring(0, MAX_ERROR_BODY_LENGTH) + "…";
    }

    public void linkRichMenu(
            String channelAccessToken, String lineUserId, String richMenuId) {
        restClient.post()
                .uri(apiBaseUrl()
                        + "/v2/bot/user/"
                        + lineUserId
                        + "/richmenu/"
                        + richMenuId)
                .header("Authorization", "Bearer " + channelAccessToken)
                .retrieve()
                .toBodilessEntity();
    }

    public void unlinkRichMenu(String channelAccessToken, String lineUserId) {
        restClient.delete()
                .uri(apiBaseUrl() + "/v2/bot/user/" + lineUserId + "/richmenu")
                .header("Authorization", "Bearer " + channelAccessToken)
                .retrieve()
                .toBodilessEntity();
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

    private String apiBaseUrl() {
        return properties.getLineApiBaseUrl().replaceAll("/+$", "");
    }

    private String dataApiBaseUrl() {
        return properties.getLineApiDataBaseUrl().replaceAll("/+$", "");
    }
}
