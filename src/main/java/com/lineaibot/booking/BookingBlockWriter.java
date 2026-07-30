package com.lineaibot.booking;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingBlockWriter {

    private final BookingRepository repository;
    private final BookingEventRepository events;

    public BookingBlockWriter(
            BookingRepository repository, BookingEventRepository events) {
        this.repository = repository;
        this.events = events;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BookingRepository.BookingBlockRow insert(
            String tenantId,
            Instant startsAt,
            Instant endsAt,
            String reason,
            String staffId,
            Instant createdAt) {
        String blockId = UUID.randomUUID().toString();
        var block = repository.insertBlock(
                blockId, tenantId, startsAt, endsAt, reason, staffId, createdAt);
        repository.insertSlotOccupancy(
                UUID.randomUUID().toString(),
                tenantId,
                startsAt,
                endsAt,
                "BLOCK",
                blockId,
                createdAt);
        events.insertActivity(
                tenantId,
                null,
                blockId,
                "SLOT_BLOCKED",
                "STAFF",
                staffId,
                reason,
                createdAt);
        return block;
    }
}
