package com.lineaibot.line;

import com.lineaibot.booking.BookingManager;
import com.lineaibot.booking.BookingRepository;
import com.lineaibot.booking.BookingAccessTokenService;
import com.lineaibot.config.AppProperties;
import com.lineaibot.knowledge.KnowledgeService;
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

@Service
public class ConversationService {

    private static final DateTimeFormatter SLOT_LABEL =
            DateTimeFormatter.ofPattern("MM/dd HH:mm");

    private final BookingManager bookings;
    private final BookingRepository bookingRepository;
    private final KnowledgeService knowledge;
    private final LineRepository repository;
    private final BookingAccessTokenService bookingAccessTokens;
    private final HandoffNotificationService handoffNotifications;
    private final AppProperties properties;
    private final ConversationUnderstandingService understanding;

    public ConversationService(
            BookingManager bookings,
            BookingRepository bookingRepository,
            KnowledgeService knowledge,
            LineRepository repository,
            BookingAccessTokenService bookingAccessTokens,
            HandoffNotificationService handoffNotifications,
            AppProperties properties,
            ConversationUnderstandingService understanding) {
        this.bookings = bookings;
        this.bookingRepository = bookingRepository;
        this.knowledge = knowledge;
        this.repository = repository;
        this.bookingAccessTokens = bookingAccessTokens;
        this.handoffNotifications = handoffNotifications;
        this.properties = properties;
        this.understanding = understanding;
    }

    public ConversationContext.Reply handleText(
            TenantRow tenant, String lineUserId, String text, ConversationContext.History history) {
        if (text.strip().matches("(?:重新開始|清除對話|重設對話|reset)")) {
            return ConversationContext.Reply.plain(List.of(textMessage("好的，已重新開始。請告訴我想詢問的問題。")));
        }
        var resolved = understanding.understand(tenant.id(), lineUserId, text, history);
        if (resolved.needsClarification()) {
            return new ConversationContext.Reply(List.of(textMessage(resolved.clarificationQuestion())),
                    new ConversationContext.State(resolved.topic(), resolved.standaloneQuestion(),
                            resolved.clarificationQuestion(), "", ""));
        }
        List<Map<String, Object>> messages = switch (resolved.intent()) {
            case "HUMAN_HANDOFF" ->
                    createHandoff(tenant, lineUserId, "使用者要求人工客服", history);
            case "BOOKING" -> bookingOptions(tenant, lineUserId);
            case "CANCEL_BOOKING" -> cancellationOptions(tenant, lineUserId);
            case "ACKNOWLEDGEMENT" -> List.of(textMessage("好的，有其他問題都可以再問我。"));
            default -> knowledgeAnswer(tenant, lineUserId, resolved.standaloneQuestion());
        };
        return new ConversationContext.Reply(messages, "KNOWLEDGE".equals(resolved.intent())
                ? new ConversationContext.State(resolved.topic(), resolved.standaloneQuestion(), "", "", "")
                : ConversationContext.State.empty());
    }

    public ConversationContext.Reply handlePostback(
            TenantRow tenant,
            String lineUserId,
            String data,
            String webhookEventId,
            ConversationContext.History history) {
        Map<String, String> values = parseQuery(data);
        if ("cancel".equals(values.get("action"))) {
            String id = values.getOrDefault("reservation_id", "");
            boolean owned = bookings.upcomingReservations(tenant.id(), lineUserId, 100).stream()
                    .anyMatch(reservation -> reservation.id().equals(id));
            if (!owned) return ConversationContext.Reply.plain(List.of(textMessage("找不到這筆預約，請重新查詢。")));
            String confirmation = java.util.UUID.randomUUID().toString();
            Map<String, Object> message = new LinkedHashMap<>(textMessage("確定要取消這筆預約嗎？預約編號：" + id));
            message.put("quickReply", Map.of("items", List.of(
                    Map.of("type", "action", "action", Map.of("type", "postback", "label", "確定取消",
                            "data", query(Map.of("action", "cancel_confirm", "reservation_id", id, "confirmation", confirmation)))),
                    Map.of("type", "action", "action", Map.of("type", "postback", "label", "保留預約", "data", "action=keep_booking")))));
            return new ConversationContext.Reply(List.of(message), new ConversationContext.State("", "", "", id, confirmation));
        }
        if ("cancel_confirm".equals(values.get("action"))) {
            var state = history.latestState();
            if (state.cancellationId().isBlank() || !state.cancellationId().equals(values.get("reservation_id"))
                    || !state.confirmationToken().equals(values.get("confirmation"))) {
                return ConversationContext.Reply.plain(List.of(textMessage("取消確認已失效，請重新輸入「取消預約」選擇。")));
            }
            return ConversationContext.Reply.plain(cancelFromPostback(tenant, lineUserId, values));
        }
        List<Map<String, Object>> messages = switch (values.getOrDefault("action", "")) {
            case "keep_booking" -> List.of(textMessage("好的，已保留原預約。"));
            case "handoff" -> createHandoff(tenant, lineUserId, "使用者點選人工客服", history);
            default -> List.of(textMessage("無法辨識這個操作，請重新選擇。"));
        };
        return ConversationContext.Reply.plain(messages);
    }

    private List<Map<String, Object>> bookingOptions(TenantRow tenant, String lineUserId) {
        var services = bookingRepository.findActiveServices(tenant.id());
        if (services.isEmpty()) {
            return List.of(textMessage("商家尚未設定可預約服務，請聯絡人工客服。"));
        }
        var service = services.getFirst();
        if (bookings.nextAvailableSlots(tenant, service.id(), 14, 1).isEmpty()) {
            return List.of(textMessage("未來兩週目前沒有可預約時段，請聯絡人工客服。"));
        }
        String token = bookingAccessTokens.issue(tenant.id(), tenant.slug(), lineUserId);
        String bookingUrl = properties.getPublicBaseUrl().replaceAll("/+$", "")
                + "/booking/"
                + tenant.slug()
                + "#token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
        Map<String, Object> openBookingPage = Map.of(
                "type", "action",
                "action", Map.of(
                        "type", "uri",
                        "label", "開啟預約頁",
                        "uri", bookingUrl));
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "text");
        message.put(
                "text",
                "請點選下方「開啟預約頁」按鈕，選擇服務、時段並填寫預約姓名。");
        message.put("quickReply", Map.of("items", List.of(openBookingPage)));
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
            TenantRow tenant, String lineUserId, String reason, ConversationContext.History history) {
        String summary = history.turns().stream()
                .skip(Math.max(0, history.turns().size() - 3))
                .map(turn -> turn.state().question())
                .filter(question -> !question.isBlank())
                .collect(java.util.stream.Collectors.joining("；"));
        summary = truncate(summary, 1000);
        String details = reason + (summary.isBlank() ? "" : "\n最近詢問（供接手參考）：" + summary);
        String ticketId = repository.findOpenHandoffId(tenant.id(), lineUserId)
                .orElseGet(() -> repository.insertHandoff(
                        tenant.id(), lineUserId, details, Instant.now()));
        repository.updateHandoffReason(tenant.id(), lineUserId, ticketId, details);
        handoffNotifications.notifyMerchant(tenant, ticketId, summary);
        return List.of(textMessage("已通知人工客服，服務人員會儘快回覆您。"));
    }

    private Map<String, Object> textMessage(String text) {
        return Map.of("type", "text", "text", text);
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

    private String truncate(String value, int length) {
        return value.substring(0, Math.min(value.length(), length));
    }
}
