package com.lineaibot.merchant;

import com.lineaibot.config.AppProperties;
import com.lineaibot.line.LineMessagingClient;
import com.lineaibot.shared.CryptoService;
import com.lineaibot.tenant.TenantRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MerchantDailySummaryWorker {

    private static final Logger log =
            LoggerFactory.getLogger(MerchantDailySummaryWorker.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final MerchantRepository repository;
    private final MerchantStaffService staffService;
    private final MerchantBookingService bookings;
    private final MerchantManageTokenService manageTokens;
    private final TenantRepository tenants;
    private final LineMessagingClient lineClient;
    private final CryptoService crypto;
    private final AppProperties properties;

    public MerchantDailySummaryWorker(
            MerchantRepository repository,
            MerchantStaffService staffService,
            MerchantBookingService bookings,
            MerchantManageTokenService manageTokens,
            TenantRepository tenants,
            LineMessagingClient lineClient,
            CryptoService crypto,
            AppProperties properties) {
        this.repository = repository;
        this.staffService = staffService;
        this.bookings = bookings;
        this.manageTokens = manageTokens;
        this.tenants = tenants;
        this.lineClient = lineClient;
        this.crypto = crypto;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void runOnce() {
        if (!properties.isLineWorkerEnabled()) {
            return;
        }
        Instant now = Instant.now();
        for (var candidate : repository.listDailySummaryCandidates()) {
            ZoneId zone = ZoneId.of(candidate.timezone());
            var localNow = now.atZone(zone);
            LocalTime configured = candidate.staff().dailySummaryTime();
            if (localNow.getHour() != configured.getHour()
                    || localNow.getMinute() != configured.getMinute()) {
                continue;
            }
            LocalDate localDate = localNow.toLocalDate();
            if (!repository.claimDailySummary(
                    candidate.tenantId(), candidate.staff().id(), localDate, now)) {
                continue;
            }
            try {
                send(candidate, localDate);
            } catch (RuntimeException exception) {
                repository.releaseDailySummaryClaim(
                        candidate.tenantId(), candidate.staff().id(), localDate);
                log.warn(
                        "Daily booking summary failed tenantId={} staffId={} message={}",
                        candidate.tenantId(),
                        candidate.staff().id(),
                        exception.getMessage());
            }
        }
    }

    private void send(
            MerchantRepository.DailySummaryCandidate candidate, LocalDate localDate) {
        var tenant = tenants.findById(candidate.tenantId()).orElseThrow();
        var agenda = bookings.agendaForLocalDates(
                tenant, localDate, localDate.plusDays(1));
        var reservations = agenda.reservations().stream()
                .filter(item -> "CONFIRMED".equals(item.status()))
                .toList();
        StringBuilder text = new StringBuilder("今日預約摘要：")
                .append(reservations.size())
                .append(" 筆");
        ZoneId zone = ZoneId.of(candidate.timezone());
        reservations.stream().limit(10).forEach(item -> text.append("\n")
                .append(TIME.format(item.startsAt().atZone(zone)))
                .append("　")
                .append(item.customerName())
                .append("｜")
                .append(item.serviceName()));
        if (reservations.size() > 10) {
            text.append("\n其餘請開啟預約月曆查看。");
        }

        var channel = tenants.findLineChannel(candidate.tenantId())
                .filter(TenantRepository.LineChannelRow::enabled)
                .orElseThrow(() -> new IllegalStateException("LINE channel is unavailable"));
        String channelToken = crypto.decryptSecret(
                properties.getEncryptionKey(), channel.channelAccessTokenEncrypted());
        String lineUserId =
                staffService.decryptLineUserId(candidate.lineUserIdEncrypted());
        String token = manageTokens.issue(candidate.tenantId(), candidate.staff().id());
        String manageUrl = properties.getPublicBaseUrl().replaceAll("/+$", "")
                + "/merchant-booking/"
                + candidate.tenantSlug()
                + "#token="
                + URLEncoder.encode(token, StandardCharsets.UTF_8);
        Map<String, Object> message = Map.of(
                "type",
                "text",
                "text",
                text.toString(),
                "quickReply",
                Map.of(
                        "items",
                        List.of(Map.of(
                                "type",
                                "action",
                                "action",
                                Map.of(
                                        "type",
                                        "uri",
                                        "label",
                                        "開啟預約月曆",
                                        "uri",
                                        manageUrl)))));
        lineClient.push(
                candidate.tenantId(),
                channelToken,
                lineUserId,
                List.of(message),
                "daily-summary:"
                        + candidate.tenantId()
                        + ":"
                        + candidate.staff().id()
                        + ":"
                        + localDate);
    }
}
