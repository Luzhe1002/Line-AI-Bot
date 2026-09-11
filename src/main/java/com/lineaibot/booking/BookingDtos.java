package com.lineaibot.booking;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public final class BookingDtos {

    private BookingDtos() {}

    public record AvailabilitySlot(Instant startsAt, Instant endsAt) {}

    public record AvailabilityResponse(
            String tenantId,
            String serviceId,
            List<String> addOnIds,
            LocalDate localDate,
            String timezone,
            int durationMinutes,
            int totalPriceAmount,
            List<AvailabilitySlot> slots) {}

    public record PublicBookingAddOn(
            String id,
            String name,
            String description,
            int durationMinutes,
            int priceAmount) {}

    public record PublicBookingService(
            String id,
            String name,
            String description,
            int durationMinutes,
            int priceAmount,
            List<PublicBookingAddOn> addOns) {}

    public record PublicBookingBootstrap(
            String tenantName,
            String tenantSlug,
            String timezone,
            int slotMinutes,
            String currency,
            List<PublicBookingService> services) {}

    public record PublicReservationCreate(
            @NotBlank String serviceId,
            List<String> addOnIds,
            OffsetDateTime startsAt,
            @NotBlank @Size(max = 160) String customerName,
            @Size(min = 8, max = 128) String idempotencyKey) {}

    public record ReservationCreate(
            @NotBlank String serviceId,
            List<String> addOnIds,
            @NotBlank @Size(max = 64) String lineUserId,
            OffsetDateTime startsAt,
            @Size(max = 160) String customerName,
            @NotBlank @Size(min = 8, max = 128) String idempotencyKey) {}

    public record ReservationRead(
            String id,
            String tenantId,
            String serviceId,
            String serviceName,
            List<ReservationAddOnRead> addOns,
            String lineUserId,
            String customerName,
            Instant startsAt,
            Instant endsAt,
            int totalDurationMinutes,
            int totalPriceAmount,
            String status,
            String idempotencyKey,
            Instant createdAt,
            Instant cancelledAt) {}

    public record ReservationAddOnRead(
            String id,
            String name,
            int durationMinutes,
            int priceAmount) {}
}
