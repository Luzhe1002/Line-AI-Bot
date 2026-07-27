package com.lineaibot.booking;

import com.lineaibot.booking.BookingDtos.ReservationRead;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReservationWriter {

    private final BookingRepository repository;

    public ReservationWriter(BookingRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(ReservationRead reservation) {
        repository.insert(reservation);
    }
}
