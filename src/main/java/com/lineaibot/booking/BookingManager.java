package com.lineaibot.booking;

import com.lineaibot.booking.BookingDtos.AvailabilitySlot;
import com.lineaibot.booking.BookingDtos.ReservationCreate;
import com.lineaibot.booking.BookingDtos.ReservationRead;
import com.lineaibot.shared.ApiException;
import com.lineaibot.tenant.TenantRepository.TenantRow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingManager {

    private final BookingRepository repository;
    private final ReservationWriter writer;

    public BookingManager(BookingRepository repository, ReservationWriter writer) {
        this.repository = repository;
        this.writer = writer;
    }

    public List<AvailabilitySlot> listAvailableSlots(
            TenantRow tenant, String serviceId, LocalDate localDate) {
        return listAvailableSlots(tenant, serviceId, localDate, Instant.now());
    }

    public List<AvailabilitySlot> listAvailableSlots(
            TenantRow tenant, String serviceId, LocalDate localDate, Instant now) {
        var bookingService = requireService(tenant.id(), serviceId);
        if (!bookingService.active()) {
            return List.of();
        }
        int weekday = localDate.getDayOfWeek().getValue() - 1;
        var hours = repository.findActiveBusinessHour(tenant.id(), weekday);
        if (hours.isEmpty()) {
            return List.of();
        }

        var zone = java.time.ZoneId.of(tenant.timezone());
        ZonedDateTime localOpen = localDate.atTime(hours.get().openTime()).atZone(zone);
        ZonedDateTime localClose = localDate.atTime(hours.get().closeTime()).atZone(zone);
        Set<Instant> reserved = new HashSet<>(repository.findReservedStarts(
                tenant.id(), localOpen.toInstant(), localClose.toInstant()));

        List<AvailabilitySlot> result = new ArrayList<>();
        ZonedDateTime cursor = localOpen;
        while (!cursor.plusMinutes(tenant.slotMinutes()).isAfter(localClose)) {
            Instant startsAt = cursor.toInstant();
            Instant endsAt = cursor.plusMinutes(tenant.slotMinutes()).toInstant();
            if (startsAt.isAfter(now) && !reserved.contains(startsAt)) {
                result.add(new AvailabilitySlot(startsAt, endsAt));
            }
            cursor = cursor.plusMinutes(tenant.slotMinutes());
        }
        return result;
    }

    public List<AvailabilitySlot> nextAvailableSlots(
            TenantRow tenant, String serviceId, int days, int limit) {
        Instant now = Instant.now();
        LocalDate localToday = now.atZone(java.time.ZoneId.of(tenant.timezone())).toLocalDate();
        List<AvailabilitySlot> result = new ArrayList<>();
        for (int offset = 0; offset < days && result.size() < limit; offset++) {
            result.addAll(listAvailableSlots(
                    tenant, serviceId, localToday.plusDays(offset), now));
        }
        return result.stream().limit(limit).toList();
    }

    public ReservationRead createReservation(TenantRow tenant, ReservationCreate request) {
        if (request.startsAt() == null) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "starts_at must include a timezone offset");
        }
        return createReservation(
                tenant,
                request.serviceId(),
                request.lineUserId(),
                request.startsAt().toInstant(),
                request.customerName(),
                request.idempotencyKey());
    }

    public ReservationRead createReservation(
            TenantRow tenant,
            String serviceId,
            String lineUserId,
            Instant startsAt,
            String customerName,
            String idempotencyKey) {
        var existing = repository.findByIdempotency(tenant.id(), idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        requireService(tenant.id(), serviceId);
        validateSlot(tenant, startsAt);

        Instant now = Instant.now();
        ReservationRead reservation = new ReservationRead(
                UUID.randomUUID().toString(),
                tenant.id(),
                serviceId,
                lineUserId,
                customerName,
                startsAt,
                startsAt.plus(tenant.slotMinutes(), ChronoUnit.MINUTES),
                "CONFIRMED",
                idempotencyKey,
                now,
                null);
        try {
            writer.insert(reservation);
            return reservation;
        } catch (DataIntegrityViolationException exception) {
            return repository.findByIdempotency(tenant.id(), idempotencyKey)
                    .orElseThrow(() -> new ApiException(
                            HttpStatus.CONFLICT,
                            "The selected slot is no longer available"));
        }
    }

    @Transactional
    public ReservationRead cancelReservation(
            String tenantId, String reservationId, String lineUserId) {
        ReservationRead reservation = repository.findById(tenantId, reservationId, lineUserId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "Reservation not found"));
        if ("CANCELLED".equals(reservation.status())) {
            return reservation;
        }
        Instant cancelledAt = Instant.now();
        repository.cancel(tenantId, reservationId, cancelledAt);
        return new ReservationRead(
                reservation.id(),
                reservation.tenantId(),
                reservation.serviceId(),
                reservation.lineUserId(),
                reservation.customerName(),
                reservation.startsAt(),
                reservation.endsAt(),
                "CANCELLED",
                reservation.idempotencyKey(),
                reservation.createdAt(),
                cancelledAt);
    }

    public List<ReservationRead> listReservations(String tenantId) {
        return repository.findAll(tenantId);
    }

    public List<ReservationRead> upcomingReservations(
            String tenantId, String lineUserId, int limit) {
        return repository.findUpcomingForUser(tenantId, lineUserId, Instant.now(), limit);
    }

    private BookingRepository.ServiceRow requireService(String tenantId, String serviceId) {
        return repository.findService(tenantId, serviceId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "Booking service not found"));
    }

    private void validateSlot(TenantRow tenant, Instant startsAt) {
        Instant now = Instant.now();
        if (!startsAt.isAfter(now)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "Cannot reserve a past slot");
        }
        var zone = java.time.ZoneId.of(tenant.timezone());
        ZonedDateTime localStart = startsAt.atZone(zone);
        int weekday = localStart.getDayOfWeek().getValue() - 1;
        var hours = repository.findActiveBusinessHour(tenant.id(), weekday)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "The business is closed on the selected day"));
        ZonedDateTime dayOpen =
                localStart.toLocalDate().atTime(hours.openTime()).atZone(zone);
        ZonedDateTime dayClose =
                localStart.toLocalDate().atTime(hours.closeTime()).atZone(zone);
        ZonedDateTime localEnd = localStart.plusMinutes(tenant.slotMinutes());
        if (localStart.isBefore(dayOpen) || localEnd.isAfter(dayClose)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "The selected slot is outside business hours");
        }
        long minutesFromOpen = ChronoUnit.MINUTES.between(dayOpen, localStart);
        if (minutesFromOpen % tenant.slotMinutes() != 0) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "The selected time is not aligned to a bookable slot");
        }
    }
}
