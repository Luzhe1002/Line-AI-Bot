package com.lineaibot.booking;

import com.lineaibot.booking.BookingDtos.ReservationRead;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationWriter {

    private final BookingRepository repository;
    private final BookingEventRepository events;

    public ReservationWriter(
            BookingRepository repository, BookingEventRepository events) {
        this.repository = repository;
        this.events = events;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(
            ReservationRead reservation,
            int slotMinutes,
            String actorType,
            String actorId) {
        repository.insert(reservation);
        for (var addOn : reservation.addOns()) {
            repository.insertReservationAddOn(
                    reservation.tenantId(), reservation.id(), addOn, reservation.createdAt());
        }
        for (java.time.Instant slotStart = reservation.startsAt();
                slotStart.isBefore(reservation.endsAt());
                slotStart = slotStart.plus(slotMinutes, java.time.temporal.ChronoUnit.MINUTES)) {
            repository.insertSlotOccupancy(
                    java.util.UUID.randomUUID().toString(),
                    reservation.tenantId(),
                    slotStart,
                    slotStart.plus(slotMinutes, java.time.temporal.ChronoUnit.MINUTES),
                    "RESERVATION",
                    reservation.id(),
                    reservation.createdAt());
        }
        events.insertEvent(
                reservation.tenantId(),
                reservation.id(),
                "RESERVATION_CREATED",
                actorType,
                actorId,
                reservation.createdAt());
        events.insertActivity(
                reservation.tenantId(),
                reservation.id(),
                null,
                "RESERVATION_CREATED",
                actorType,
                actorId,
                null,
                reservation.createdAt());
    }
}
