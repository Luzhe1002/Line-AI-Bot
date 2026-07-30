package com.lineaibot.merchant;

import com.lineaibot.config.AppProperties;
import com.lineaibot.shared.ApiException;
import com.lineaibot.tenant.TenantRepository.TenantRow;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class MerchantLineService {

    private static final Pattern BIND_COMMAND =
            Pattern.compile("^\\s*綁定\\s*([A-Za-z0-9-]{6,20})\\s*$");
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("MM/dd（E）HH:mm", Locale.TAIWAN);

    private final MerchantStaffService staffService;
    private final MerchantBookingService bookings;
    private final MerchantManageTokenService manageTokens;
    private final AppProperties properties;

    public MerchantLineService(
            MerchantStaffService staffService,
            MerchantBookingService bookings,
            MerchantManageTokenService manageTokens,
            AppProperties properties) {
        this.staffService = staffService;
        this.bookings = bookings;
        this.manageTokens = manageTokens;
        this.properties = properties;
    }

    public Optional<List<Map<String, Object>>> handleText(
            TenantRow tenant, String lineUserId, String text) {
        Matcher binding = BIND_COMMAND.matcher(text == null ? "" : text);
        if (binding.matches()) {
            try {
                var staff = staffService.bind(tenant.id(), lineUserId, binding.group(1));
                return Optional.of(List.of(textMessage(
                        "管理員綁定成功！\n已綁定「"
                                + staff.displayName()
                                + "」的店家管理權限。\n"
                                + "輸入「管理預約」即可開始使用。")));
            } catch (ApiException exception) {
                return Optional.of(List.of(textMessage(exception.getMessage())));
            }
        }

        var staff = staffService.findActive(tenant.id(), lineUserId);
        if (staff.isEmpty()) {
            return Optional.empty();
        }
        String normalized = normalize(text);
        return switch (normalized) {
            case "今日預約", "今天預約" ->
                    Optional.of(agendaMessages(tenant, staff.get(), "today"));
            case "明日預約", "明天預約" ->
                    Optional.of(agendaMessages(tenant, staff.get(), "tomorrow"));
            case "本週預約", "這週預約" ->
                    Optional.of(agendaMessages(tenant, staff.get(), "week"));
            case "管理預約", "預約管理", "店家管理" ->
                    Optional.of(managementMenu(tenant, staff.get()));
            default -> Optional.empty();
        };
    }

    public Optional<List<Map<String, Object>>> handlePostback(
            TenantRow tenant, String lineUserId, String data) {
        Map<String, String> values = parseQuery(data);
        String action = values.getOrDefault("action", "");
        if (!action.startsWith("merchant_")) {
            return Optional.empty();
        }
        var staff = staffService.findActive(tenant.id(), lineUserId);
        if (staff.isEmpty()) {
            return Optional.of(List.of(textMessage("這個 LINE 尚未綁定店家管理權限。")));
        }
        return Optional.of(switch (action) {
            case "merchant_agenda" -> agendaMessages(
                    tenant, staff.get(), values.getOrDefault("range", "today"));
            case "merchant_cancel_prompt" ->
                    cancellationPrompt(
                            tenant,
                            staff.get(),
                            require(values, "reservation_id"));
            case "merchant_cancel_confirm" ->
                    cancelReservation(
                            tenant,
                            staff.get(),
                            require(values, "reservation_id"));
            case "merchant_menu" -> managementMenu(tenant, staff.get());
            default -> List.of(textMessage("無法辨識這個店家管理操作。"));
        });
    }

    private List<Map<String, Object>> managementMenu(
            TenantRow tenant, MerchantDtos.StaffView staff) {
        String manageUrl = manageUrl(tenant, staff);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "text");
        message.put("text", staff.displayName() + "，請選擇要管理的預約範圍：");
        message.put("quickReply", Map.of(
                "items",
                List.of(
                        postbackItem(
                                "今日預約",
                                query(Map.of(
                                        "action", "merchant_agenda",
                                        "range", "today"))),
                        postbackItem(
                                "明日預約",
                                query(Map.of(
                                        "action", "merchant_agenda",
                                        "range", "tomorrow"))),
                        postbackItem(
                                "本週預約",
                                query(Map.of(
                                        "action", "merchant_agenda",
                                        "range", "week"))),
                        uriItem("開啟預約月曆", manageUrl))));
        return List.of(message);
    }

    private List<Map<String, Object>> agendaMessages(
            TenantRow tenant, MerchantDtos.StaffView staff, String range) {
        ZoneId zone = ZoneId.of(tenant.timezone());
        LocalDate today = LocalDate.now(zone);
        LocalDate fromDate;
        LocalDate toDate;
        String title;
        switch (range) {
            case "tomorrow" -> {
                fromDate = today.plusDays(1);
                toDate = fromDate.plusDays(1);
                title = "明日預約";
            }
            case "week" -> {
                fromDate = today;
                toDate = today.plusDays(7);
                title = "未來七天預約";
            }
            default -> {
                fromDate = today;
                toDate = today.plusDays(1);
                title = "今日預約";
            }
        }
        var agenda = bookings.agendaForLocalDates(tenant, fromDate, toDate);
        var active = agenda.reservations().stream()
                .filter(item -> "CONFIRMED".equals(item.status()))
                .toList();
        if (active.isEmpty()) {
            return List.of(withQuickReply(
                    title + "目前沒有已確認預約。",
                    List.of(uriItem("開啟預約月曆", manageUrl(tenant, staff)))));
        }

        StringBuilder text = new StringBuilder(title)
                .append("（")
                .append(active.size())
                .append(" 筆）");
        active.stream().limit(10).forEach(item -> text.append("\n\n")
                .append(DATE_TIME.format(item.startsAt().atZone(zone)))
                .append("\n")
                .append(item.customerName())
                .append("｜")
                .append(item.serviceName())
                .append("\n編號：")
                .append(shortId(item.id())));
        if (active.size() > 10) {
            text.append("\n\n其餘請開啟預約月曆查看。");
        }

        List<Map<String, Object>> items = new ArrayList<>();
        if (staffService.canMutateBookings(staff)) {
            active.stream().limit(8).forEach(item -> items.add(postbackItem(
                    "取消 " + timeOnly(item.startsAt(), zone) + " " + shortName(item.customerName()),
                    query(Map.of(
                            "action", "merchant_cancel_prompt",
                            "reservation_id", item.id())))));
        }
        items.add(uriItem("開啟預約月曆", manageUrl(tenant, staff)));
        return List.of(withQuickReply(text.toString(), items));
    }

    private List<Map<String, Object>> cancellationPrompt(
            TenantRow tenant,
            MerchantDtos.StaffView staff,
            String reservationId) {
        if (!staffService.canMutateBookings(staff)) {
            return List.of(textMessage("你的權限只能查看預約，不能取消。"));
        }
        var reservation =
                bookings.requireReservationSummary(tenant.id(), reservationId);
        if ("CANCELLED".equals(reservation.status())) {
            return List.of(textMessage("這筆預約已經取消。"));
        }
        String text = "確定要取消以下預約？\n"
                + DATE_TIME.format(
                        reservation.startsAt().atZone(ZoneId.of(tenant.timezone())))
                + "\n"
                + reservation.customerName()
                + "｜"
                + reservation.serviceName();
        return List.of(withQuickReply(
                text,
                List.of(postbackItem(
                        "確認取消",
                        query(Map.of(
                                "action", "merchant_cancel_confirm",
                                "reservation_id", reservation.id()))))));
    }

    private List<Map<String, Object>> cancelReservation(
            TenantRow tenant,
            MerchantDtos.StaffView staff,
            String reservationId) {
        try {
            var cancelled = bookings.cancel(tenant.id(), reservationId, staff);
            return List.of(textMessage(
                    "預約已取消，系統會通知顧客。\n預約編號：" + cancelled.id()));
        } catch (ApiException exception) {
            return List.of(textMessage(exception.getMessage()));
        }
    }

    private String manageUrl(TenantRow tenant, MerchantDtos.StaffView staff) {
        String token = manageTokens.issue(tenant.id(), staff.id());
        return properties.getPublicBaseUrl().replaceAll("/+$", "")
                + "/merchant-booking/"
                + tenant.slug()
                + "#token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    private Map<String, Object> withQuickReply(
            String text, List<Map<String, Object>> items) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "text");
        message.put("text", text);
        if (!items.isEmpty()) {
            message.put("quickReply", Map.of("items", items));
        }
        return message;
    }

    private Map<String, Object> textMessage(String text) {
        return Map.of("type", "text", "text", text);
    }

    private Map<String, Object> postbackItem(String label, String data) {
        String safeLabel = label.substring(0, Math.min(label.length(), 20));
        return Map.of(
                "type",
                "action",
                "action",
                Map.of(
                        "type",
                        "postback",
                        "label",
                        safeLabel,
                        "data",
                        data,
                        "displayText",
                        safeLabel));
    }

    private Map<String, Object> uriItem(String label, String uri) {
        return Map.of(
                "type",
                "action",
                "action",
                Map.of("type", "uri", "label", label, "uri", uri));
    }

    private Map<String, String> parseQuery(String data) {
        Map<String, String> values = new LinkedHashMap<>();
        if (data == null) {
            return values;
        }
        for (String pair : data.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) {
                values.put(
                        java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
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

    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").strip();
    }

    private String shortId(String id) {
        return id.substring(0, Math.min(id.length(), 8)).toUpperCase(Locale.ROOT);
    }

    private String shortName(String name) {
        return name.substring(0, Math.min(name.length(), 6));
    }

    private String timeOnly(java.time.Instant instant, ZoneId zone) {
        return java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                .format(instant.atZone(zone));
    }
}
