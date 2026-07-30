package com.lineaibot.booking;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BookingEventRepository {

    public record BookingEventRow(
            String id,
            String tenantId,
            String reservationId,
            String eventType,
            String actorType,
            String actorId,
            String status,
            int attempts) {}

    private final JdbcClient jdbc;

    public BookingEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertEvent(
            String tenantId,
            String reservationId,
            String eventType,
            String actorType,
            String actorId,
            Instant createdAt) {
        jdbc.sql("""
                        insert into booking_events (
                            id, tenant_id, reservation_id, event_type,
                            actor_type, actor_id, dedupe_key, status,
                            attempts, next_attempt_at, locked_at, error,
                            created_at, processed_at
                        ) values (
                            :id, :tenantId, :reservationId, :eventType,
                            :actorType, :actorId, :dedupeKey, 'PENDING',
                            0, :createdAt, null, null, :createdAt, null
                        )
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("tenantId", tenantId)
                .param("reservationId", reservationId)
                .param("eventType", eventType)
                .param("actorType", actorType)
                .param("actorId", actorId)
                .param("dedupeKey", eventType + ":" + reservationId)
                .param("createdAt", utc(createdAt))
                .update();
    }

    public void insertActivity(
            String tenantId,
            String reservationId,
            String blockId,
            String action,
            String actorType,
            String actorId,
            String details,
            Instant createdAt) {
        jdbc.sql("""
                        insert into booking_activity_logs (
                            id, tenant_id, reservation_id, block_id,
                            action, actor_type, actor_id, details, created_at
                        ) values (
                            :id, :tenantId, :reservationId, :blockId,
                            :action, :actorType, :actorId, :details, :createdAt
                        )
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("tenantId", tenantId)
                .param("reservationId", reservationId)
                .param("blockId", blockId)
                .param("action", action)
                .param("actorType", actorType)
                .param("actorId", actorId)
                .param("details", details)
                .param("createdAt", utc(createdAt))
                .update();
    }

    public List<String> findReadyEventIds(Instant now, int limit) {
        return jdbc.sql("""
                        select id from booking_events
                        where status in ('PENDING', 'RETRY')
                          and next_attempt_at <= :now
                        order by created_at
                        limit :limit
                        """)
                .param("now", utc(now))
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    public boolean claimEvent(String eventId, Instant now) {
        return jdbc.sql("""
                        update booking_events
                        set status = 'PROCESSING', attempts = attempts + 1,
                            locked_at = :now, error = null
                        where id = :id
                          and status in ('PENDING', 'RETRY')
                          and next_attempt_at <= :now
                        """)
                        .param("now", utc(now))
                        .param("id", eventId)
                        .update()
                == 1;
    }

    public Optional<BookingEventRow> findEvent(String eventId) {
        return jdbc.sql("select * from booking_events where id = :id")
                .param("id", eventId)
                .query(this::mapEvent)
                .optional();
    }

    public void markProcessed(String eventId, Instant processedAt) {
        jdbc.sql("""
                        update booking_events
                        set status = 'PROCESSED', processed_at = :processedAt,
                            locked_at = null, error = null
                        where id = :id
                        """)
                .param("processedAt", utc(processedAt))
                .param("id", eventId)
                .update();
    }

    public void markRetryOrFailed(
            String eventId, int attempts, String error, Instant nextAttemptAt) {
        String status = attempts < 5 ? "RETRY" : "FAILED";
        jdbc.sql("""
                        update booking_events
                        set status = :status, error = :error,
                            locked_at = null, next_attempt_at = :nextAttemptAt,
                            processed_at = case when :status = 'FAILED'
                                                then :nextAttemptAt else null end
                        where id = :id
                        """)
                .param("status", status)
                .param("error", truncate(error, 4000))
                .param("nextAttemptAt", utc(nextAttemptAt))
                .param("id", eventId)
                .update();
    }

    public void recoverStaleEvents(Instant staleBefore, Instant retryAt) {
        jdbc.sql("""
                        update booking_events
                        set status = case when attempts >= 5 then 'FAILED' else 'RETRY' end,
                            next_attempt_at = :retryAt, locked_at = null,
                            processed_at = case when attempts >= 5 then :retryAt else null end,
                            error = 'Recovered stale booking notification claim'
                        where status = 'PROCESSING' and locked_at < :staleBefore
                        """)
                .param("retryAt", utc(retryAt))
                .param("staleBefore", utc(staleBefore))
                .update();
    }

    private BookingEventRow mapEvent(ResultSet rs, int rowNum) throws SQLException {
        return new BookingEventRow(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("reservation_id"),
                rs.getString("event_type"),
                rs.getString("actor_type"),
                rs.getString("actor_id"),
                rs.getString("status"),
                rs.getInt("attempts"));
    }

    private String truncate(String value, int length) {
        if (value == null) {
            return null;
        }
        return value.substring(0, Math.min(length, value.length()));
    }

    private OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
