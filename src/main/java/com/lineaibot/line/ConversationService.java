package com.lineaibot.line;

import com.lineaibot.booking.BookingManager;
import com.lineaibot.booking.BookingRepository;
import com.lineaibot.knowledge.KnowledgeService;
import com.lineaibot.shared.ApiException;
import com.lineaibot.tenant.TenantRepository.TenantRow;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ConversationService {

    private static final DateTimeFormatter SLOT_LABEL =
            DateTimeFormatter.ofPattern("MM/dd HH:mm");
    private static final DateTimeFormatter BOOKED_LABEL =
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    private final IntentClassifier classifier;
    private final BookingManager bookings;
    private final BookingRepository bookingRepository;
    private final KnowledgeService knowledge;
    private final LineRepository repository;
    private final ObjectMapper objectMapper;

    public ConversationService(
            IntentClassifier classifier,
            BookingManager bookings,
            BookingRepository bookingRepository,
            KnowledgeService knowledge,
            LineRepository repository,
            ObjectMapper objectMapper) {
        this.classifier = classifier;
        this.bookings = bookings;
        this.bookingRepository = bookingRepository;
        this.knowledge = knowledge;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> handleText(
            TenantRow tenant, String lineUserId, String text) {
        recordInbound(tenant.id(), lineUserId, "text", text);
        List<Map<String, Object>> messages = switch (classifier.classify(text)) {
            case HUMAN_HANDOFF ->
                    createHandoff(tenant, lineUserId, "使用者要求人工客服");
            case BOOKING -> bookingOptions(tenant);
            case CANCEL_BOOKING -> cancellationOptions(tenant, lineUserId);
            case KNOWLEDGE -> knowledgeAnswer(tenant, lineUserId, text);
        };
        recordOutbound(tenant.id(), lineUserId, messages);
        return messages;
    }

    public List<Map<String, Object>> handlePostback(
            TenantRow tenant,
            String lineUserId,
            String data,
            String webhookEventId) {
        recordInbound(tenant.id(), lineUserId, "postback", data);
        Map<String, String> values = parseQuery(data);
        List<Map<String, Object>> messages = switch (values.getOrDefault("action", "")) {
            case "book" -> bookFromPostback(tenant, lineUserId, values, webhookEventId);
            case "cancel" -> cancelFromPostback(tenant, lineUserId, values);
            case "handoff" -> createHandoff(tenant, lineUserId, "使用者點選人工客服");
            default -> List.of(textMessage("無法辨識這個操作，請重新選擇。"));
        };
        recordOutbound(tenant.id(), lineUserId, messages);
        return messages;
    }

    private List<Map<String, Object>> bookingOptions(TenantRow tenant) {
        var services = bookingRepository.findActiveServices(tenant.id());
        if (services.isEmpty()) {
            return List.of(textMessage("商家尚未設定可預約服務，請聯絡人工客服。"));
        }
        var service = services.getFirst();
        var slots = bookings.nextAvailableSlots(tenant, service.id(), 14, 10);
        if (slots.isEmpty()) {
            return List.of(textMessage("未來兩週目前沒有可預約時段，請聯絡人工客服。"));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        ZoneId zone = ZoneId.of(tenant.timezone());
        for (var slot : slots) {
            String label = SLOT_LABEL.format(slot.startsAt().atZone(zone));
            String data = query(Map.of(
                    "action", "book",
                    "service_id", service.id(),
                    "starts_at", slot.startsAt().toString()));
            items.add(Map.of(
                    "type", "action",
                    "action", Map.of(
                            "type", "postback",
                            "label", label,
                            "data", data,
                            "displayText", "我要預約 " + label)));
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "text");
        message.put("text", "請選擇「" + service.name() + "」的預約時段：");
        message.put("quickReply", Map.of("items", items));
        return List.of(message);
    }

    private List<Map<String, Object>> cancellationOptions(
            TenantRow tenant, String lineUserId) {
        var reservations = bookings.upcomingReservations(tenant.id(), lineUserId, 10);
        if (reservations.isEmpty()) {
            return List.of(textMessage("目前查不到可取消的預約。"));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        ZoneId zone = ZoneId.of(tenant.timezone());
        for (var reservation : reservations) {
            String label = "取消 " + SLOT_LABEL.format(reservation.startsAt().atZone(zone));
            items.add(Map.of(
                    "type", "action",
                    "action", Map.of(
                            "type", "postback",
                            "label", label,
                            "data", query(Map.of(
                                    "action", "cancel",
                                    "reservation_id", reservation.id())),
                            "displayText", label)));
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "text");
        message.put("text", "請選擇要取消的預約：");
        message.put("quickReply", Map.of("items", items));
        return List.of(message);
    }

    private List<Map<String, Object>> knowledgeAnswer(
            TenantRow tenant, String lineUserId, String question) {
        var answer = knowledge.answer(tenant, question, lineUserId);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "text");
        message.put("text", truncate(answer.answer(), 5000));
        if (!answer.grounded()) {
            message.put("quickReply", Map.of(
                    "items",
                    List.of(Map.of(
                            "type", "action",
                            "action", Map.of(
                                    "type", "message",
                                    "label", "轉接人工客服",
                                    "text", "我要人工客服")))));
        }
        return List.of(message);
    }

    private List<Map<String, Object>> bookFromPostback(
            TenantRow tenant,
            String lineUserId,
            Map<String, String> values,
            String webhookEventId) {
        try {
            var reservation = bookings.createReservation(
                    tenant,
                    require(values, "service_id"),
                    lineUserId,
                    Instant.parse(require(values, "starts_at")),
                    null,
                    "line:" + webhookEventId);
            String localStart = BOOKED_LABEL.format(
                    reservation.startsAt().atZone(ZoneId.of(tenant.timezone())));
            return List.of(textMessage(
                    "預約成功！時間：" + localStart + "。\n預約編號：" + reservation.id()));
        } catch (ApiException exception) {
            if (exception.status().value() == 409) {
                return List.of(textMessage("這個時段剛被預約，請輸入「預約」重新選擇。"));
            }
            return List.of(textMessage("目前無法完成預約，請聯絡人工客服。"));
        } catch (RuntimeException exception) {
            return List.of(textMessage("預約資料不完整，請重新選擇時段。"));
        }
    }

    private List<Map<String, Object>> cancelFromPostback(
            TenantRow tenant, String lineUserId, Map<String, String> values) {
        try {
            var reservation = bookings.cancelReservation(
                    tenant.id(), require(values, "reservation_id"), lineUserId);
            return List.of(textMessage("預約已取消。預約編號：" + reservation.id()));
        } catch (RuntimeException exception) {
            return List.of(textMessage("找不到這筆預約，請聯絡人工客服。"));
        }
    }

    private List<Map<String, Object>> createHandoff(
            TenantRow tenant, String lineUserId, String reason) {
        if (!repository.hasOpenHandoff(tenant.id(), lineUserId)) {
            repository.insertHandoff(tenant.id(), lineUserId, reason, Instant.now());
        }
        return List.of(textMessage("已通知人工客服，服務人員會儘快回覆您。"));
    }

    private Map<String, Object> textMessage(String text) {
        return Map.of("type", "text", "text", text);
    }

    private void recordInbound(
            String tenantId, String lineUserId, String messageType, String content) {
        repository.recordConversationMessage(
                tenantId,
                lineUserId,
                "INBOUND",
                messageType,
                content,
                null,
                Instant.now());
    }

    private void recordOutbound(
            String tenantId,
            String lineUserId,
            List<Map<String, Object>> messages) {
        for (Map<String, Object> message : messages) {
            String metadata = toJson(message);
            repository.recordConversationMessage(
                    tenantId,
                    lineUserId,
                    "OUTBOUND",
                    message.getOrDefault("type", "unknown").toString(),
                    message.getOrDefault("text", metadata).toString(),
                    metadata,
                    Instant.now());
        }
    }

    private Map<String, String> parseQuery(String data) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : data.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) {
                values.put(
                        URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private String query(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                        + "="
                        + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .reduce((first, second) -> first + "&" + second)
                .orElse("");
    }

    private String require(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return value;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize LINE message", exception);
        }
    }

    private String truncate(String value, int length) {
        return value.substring(0, Math.min(value.length(), length));
    }
}
