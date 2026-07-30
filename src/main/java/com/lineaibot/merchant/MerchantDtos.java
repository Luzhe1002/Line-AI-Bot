package com.lineaibot.merchant;

import com.lineaibot.booking.BookingDtos.AvailabilitySlot;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

public final class MerchantDtos {

    private MerchantDtos() {}

    public record StaffLinkCreate(
            @NotBlank @Size(max = 160) String displayName,
            @NotBlank @Size(max = 24) String role) {}

    public record StaffLinkView(
            String code, String displayName, String role, Instant expiresAt) {}

    public record StaffUpdate(
            @Size(max = 160) String displayName,
            @Size(max = 24) String role,
            @Size(max = 24) String status,
            Boolean notifyNewBooking,
            Boolean notifyCancellation,
            Boolean dailySummaryEnabled,
            LocalTime dailySummaryTime) {}

    public record StaffView(
            String id,
            String tenantId,
            String displayName,
            String role,
            String status,
            boolean notifyNewBooking,
            boolean notifyCancellation,
            boolean dailySummaryEnabled,
            LocalTime dailySummaryTime,
            Instant createdAt,
            Instant updatedAt) {}

    public record MerchantSessionView(
            boolean authenticated,
            String tenantName,
            String tenantSlug,
            String timezone,
            StaffView staff,
            String csrfToken) {}

    public record MerchantBookingBootstrap(
            String tenantName,
            String tenantSlug,
            String timezone,
            int slotMinutes,
            StaffView staff,
            List<ServiceOption> services) {}

    public record ServiceOption(String id, String name) {}

    public record ReservationSummary(
            String id,
            String serviceId,
            String serviceName,
            String customerName,
            Instant startsAt,
            Instant endsAt,
            String status,
            Instant createdAt) {}

    public record BookingBlockView(
            String id,
            Instant startsAt,
            Instant endsAt,
            String reason,
            boolean active,
            String createdByStaffId,
            Instant createdAt,
            Instant releasedAt) {}

    public record AgendaResponse(
            Instant from,
            Instant to,
            List<ReservationSummary> reservations,
            List<BookingBlockView> blocks) {}

    public record BlockCreate(
            OffsetDateTime startsAt, @Size(max = 500) String reason) {}

    public record AvailabilityView(
            LocalDate localDate, String timezone, List<AvailabilitySlot> slots) {}
}
