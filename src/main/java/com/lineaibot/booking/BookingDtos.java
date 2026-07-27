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
            LocalDate localDate,
            String timezone,
            List<AvailabilitySlot> slots) {}

    public record ReservationCreate(
            @NotBlank String serviceId,
            @NotBlank @Size(max = 64) String lineUserId,
            OffsetDateTime startsAt,
            @Size(max = 160) String customerName,
            @NotBlank @Size(min = 8, max = 128) String idempotencyKey) {}

    public record ReservationRead(
            String id,
            String tenantId,
            String serviceId,
            String lineUserId,
            String customerName,
            Instant startsAt,
            Instant endsAt,
            String status,
            String idempotencyKey,
            Instant createdAt,
            Instant cancelledAt) {}
}
