package com.lineaibot.booking;

import com.lineaibot.config.AppProperties;
import com.lineaibot.line.LineMessagingClient;
import com.lineaibot.merchant.MerchantManageTokenService;
import com.lineaibot.merchant.MerchantRepository;
import com.lineaibot.merchant.MerchantStaffService;
import com.lineaibot.shared.CryptoService;
import com.lineaibot.tenant.TenantRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class BookingNotificationProcessor {

    private static final Logger log =
            LoggerFactory.getLogger(BookingNotificationProcessor.class);
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy/MM/dd（E）HH:mm");

    private final BookingEventRepository events;
    private final BookingManager bookings;
    private final TenantRepository tenants;
    private final MerchantRepository merchants;
    private final MerchantStaffService staffService;
    private final MerchantManageTokenService manageTokens;
    private final LineMessagingClient lineClient;
    private final CryptoService crypto;
    private final AppProperties properties;

    public BookingNotificationProcessor(
            BookingEventRepository events,
            BookingManager bookings,
            TenantRepository tenants,
            MerchantRepository merchants,
            MerchantStaffService staffService,
            MerchantManageTokenService manageTokens,
            LineMessagingClient lineClient,
            CryptoService crypto,
            AppProperties properties) {
        this.events = events;
        this.bookings = bookings;
        this.tenants = tenants;
        this.merchants = merchants;
        this.staffService = staffService;
        this.manageTokens = manageTokens;
        this.lineClient = lineClient;
        this.crypto = crypto;
        this.properties = properties;
    }

    public void process(String eventId) {
        var event = events.findEvent(eventId).orElse(null);
        if (event == null) {
            return;
        }
        try {
            var tenant = tenants.findById(event.tenantId())
                    .filter(TenantRepository.TenantRow::active)
                    .orElseThrow(() -> new IllegalStateException("Tenant is unavailable"));
            var reservation = bookings.requireReservation(
                    event.tenantId(), event.reservationId());
            var recipients = merchants.listNotificationStaff(
                    event.tenantId(), event.eventType());
            boolean notifyCustomer = "RESERVATION_CREATED".equals(event.eventType())
                    || ("RESERVATION_CANCELLED".equals(event.eventType())
                            && !"CUSTOMER".equals(event.actorType()));
            if (recipients.isEmpty() && !notifyCustomer) {
                events.markProcessed(eventId, Instant.now());
                return;
            }
            var channel = tenants.findLineChannel(tenant.id())
                    .filter(TenantRepository.LineChannelRow::enabled)
                    .orElseThrow(() -> new IllegalStateException("LINE channel is unavailable"));
            String channelToken = crypto.decryptSecret(
                    properties.getEncryptionKey(), channel.channelAccessTokenEncrypted());

            for (MerchantRepository.StaffSecretRow recipient : recipients) {
                String lineUserId =
                        staffService.decryptLineUserId(recipient.lineUserIdEncrypted());
                String manageToken = manageTokens.issue(tenant.id(), recipient.staff().id());
                String manageUrl = properties.getPublicBaseUrl().replaceAll("/+$", "")
                        + "/merchant-booking/"
                        + tenant.slug()
                        + "#token="
                        + URLEncoder.encode(manageToken, StandardCharsets.UTF_8);
                lineClient.push(
                        tenant.id(),
                        channelToken,
                        lineUserId,
                        staffMessages(event, reservation, tenant.timezone(), manageUrl),
                        "booking-event:" + event.id() + ":staff:" + recipient.staff().id());
            }

            if (notifyCustomer) {
                lineClient.push(
                        tenant.id(),
                        channelToken,
                        reservation.lineUserId(),
                        customerMessage(event, reservation, tenant.timezone()),
                        "booking-event:" + event.id() + ":customer");
            }
            events.markProcessed(eventId, Instant.now());
        } catch (Exception exception) {
            int delaySeconds = (int) Math.pow(2, Math.max(0, event.attempts() - 1));
            events.markRetryOrFailed(
                    eventId,
                    event.attempts(),
                    exception.getMessage() == null
                            ? exception.getClass().getSimpleName()
                            : exception.getMessage(),
                    Instant.now().plusSeconds(delaySeconds));
            log.warn(
                    "Booking notification failed eventId={} tenantId={} attempt={} message={}",
                    eventId,
                    event.tenantId(),
                    event.attempts(),
                    exception.getMessage());
        }
    }

    private List<Map<String, Object>> staffMessages(
            BookingEventRepository.BookingEventRow event,
            BookingDtos.ReservationRead reservation,
            String timezone,
            String manageUrl) {
        String customerName = reservation.customerName() == null
                        || reservation.customerName().isBlank()
                ? "未填姓名"
                : reservation.customerName();
        String time = DATE_TIME.format(
                reservation.startsAt().atZone(ZoneId.of(timezone)));
        String heading = "RESERVATION_CREATED".equals(event.eventType())
                ? "收到新預約"
                : "預約已取消";
        String actor = "CUSTOMER".equals(event.actorType()) ? "（顧客操作）" : "";
        String text = heading + actor
                + "\n"
                + time
                + "\n"
                + customerName
                + "\n預約編號："
                + shortId(reservation.id());
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(postbackItem("今日預約", "action=merchant_agenda&range=today"));
        items.add(uriItem("開啟預約月曆", manageUrl));
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "text");
        message.put("text", text);
        message.put("quickReply", Map.of("items", items));
        return List.of(message);
    }

    private List<Map<String, Object>> customerMessage(
            BookingEventRepository.BookingEventRow event,
            BookingDtos.ReservationRead reservation,
            String timezone) {
        String time = DATE_TIME.format(
                reservation.startsAt().atZone(ZoneId.of(timezone)));
        String text;
        if ("RESERVATION_CREATED".equals(event.eventType())) {
            String customerName = reservation.customerName() == null
                            || reservation.customerName().isBlank()
                    ? "未填姓名"
                    : reservation.customerName();
            text = "預約成功！"
                    + "\n時間："
                    + time
                    + "\n預約姓名："
                    + customerName
                    + "\n預約編號："
                    + shortId(reservation.id());
        } else {
            text = "店家已取消您在 " + time + " 的預約。\n預約編號：" + shortId(reservation.id());
        }
        return List.of(Map.of(
                "type",
                "text",
                "text",
                text));
    }

    private Map<String, Object> postbackItem(String label, String data) {
        return Map.of(
                "type",
                "action",
                "action",
                Map.of(
                        "type",
                        "postback",
                        "label",
                        label,
                        "data",
                        data,
                        "displayText",
                        label));
    }

    private Map<String, Object> uriItem(String label, String uri) {
        return Map.of(
                "type",
                "action",
                "action",
                Map.of("type", "uri", "label", label, "uri", uri));
    }

    private String shortId(String id) {
        return id.substring(0, Math.min(id.length(), 8)).toUpperCase();
    }
}
