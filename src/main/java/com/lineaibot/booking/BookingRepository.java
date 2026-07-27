package com.lineaibot.booking;

import com.lineaibot.booking.BookingDtos.ReservationRead;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class BookingRepository {

    public record ServiceRow(
            String id, String tenantId, String name, String description, boolean active) {}

    public record BusinessHourRow(
            int weekday, LocalTime openTime, LocalTime closeTime, boolean active) {}

    private final JdbcClient jdbc;

    public BookingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ServiceRow> findService(String tenantId, String serviceId) {
        return jdbc.sql("""
                        select id, tenant_id, name, description, active
                        from booking_services
                        where id = :serviceId and tenant_id = :tenantId
                        """)
                .param("serviceId", serviceId)
                .param("tenantId", tenantId)
                .query((rs, rowNum) -> new ServiceRow(
                        rs.getString("id"),
                        rs.getString("tenant_id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getBoolean("active")))
                .optional();
    }

    public List<ServiceRow> findActiveServices(String tenantId) {
        return jdbc.sql("""
                        select id, tenant_id, name, description, active
                        from booking_services
                        where tenant_id = :tenantId and active = true
                        order by created_at
                        """)
                .param("tenantId", tenantId)
                .query((rs, rowNum) -> new ServiceRow(
                        rs.getString("id"),
                        rs.getString("tenant_id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getBoolean("active")))
                .list();
    }

    public Optional<BusinessHourRow> findActiveBusinessHour(String tenantId, int weekday) {
        return jdbc.sql("""
                        select weekday, open_time, close_time, active
                        from business_hours
                        where tenant_id = :tenantId and weekday = :weekday and active = true
                        """)
                .param("tenantId", tenantId)
                .param("weekday", weekday)
                .query((rs, rowNum) -> new BusinessHourRow(
                        rs.getInt("weekday"),
                        rs.getObject("open_time", LocalTime.class),
                        rs.getObject("close_time", LocalTime.class),
                        rs.getBoolean("active")))
                .optional();
    }

    public List<Instant> findReservedStarts(
            String tenantId, Instant windowStart, Instant windowEnd) {
        return jdbc.sql("""
                        select starts_at from reservations
                        where tenant_id = :tenantId
                          and status in ('HELD', 'CONFIRMED')
                          and starts_at >= :windowStart
                          and starts_at < :windowEnd
                        """)
                .param("tenantId", tenantId)
                .param("windowStart", utc(windowStart))
                .param("windowEnd", utc(windowEnd))
                .query((rs, rowNum) -> rs.getObject("starts_at", OffsetDateTime.class).toInstant())
                .list();
    }

    public Optional<ReservationRead> findByIdempotency(
            String tenantId, String idempotencyKey) {
        return jdbc.sql("""
                        select * from reservations
                        where tenant_id = :tenantId and idempotency_key = :idempotencyKey
                        """)
                .param("tenantId", tenantId)
                .param("idempotencyKey", idempotencyKey)
                .query(this::mapReservation)
                .optional();
    }

    public Optional<ReservationRead> findById(
            String tenantId, String reservationId, String lineUserId) {
        String sql = lineUserId == null
                ? """
                    select * from reservations
                    where tenant_id = :tenantId and id = :reservationId
                    """
                : """
                    select * from reservations
                    where tenant_id = :tenantId and id = :reservationId
                      and line_user_id = :lineUserId
                    """;
        var statement = jdbc.sql(sql)
                .param("tenantId", tenantId)
                .param("reservationId", reservationId);
        if (lineUserId != null) {
            statement = statement.param("lineUserId", lineUserId);
        }
        return statement.query(this::mapReservation).optional();
    }

    public List<ReservationRead> findAll(String tenantId) {
        return jdbc.sql("""
                        select * from reservations
                        where tenant_id = :tenantId order by starts_at
                        """)
                .param("tenantId", tenantId)
                .query(this::mapReservation)
                .list();
    }

    public List<ReservationRead> findUpcomingForUser(
            String tenantId, String lineUserId, Instant now, int limit) {
        return jdbc.sql("""
                        select * from reservations
                        where tenant_id = :tenantId
                          and line_user_id = :lineUserId
                          and status = 'CONFIRMED'
                          and starts_at > :now
                        order by starts_at
                        limit :limit
                        """)
                .param("tenantId", tenantId)
                .param("lineUserId", lineUserId)
                .param("now", utc(now))
                .param("limit", limit)
                .query(this::mapReservation)
                .list();
    }

    public void insert(ReservationRead reservation) {
        jdbc.sql("""
                        insert into reservations (
                            id, tenant_id, service_id, line_user_id, customer_name,
                            starts_at, ends_at, active_slot_key, status,
                            idempotency_key, created_at, cancelled_at
                        ) values (
                            :id, :tenantId, :serviceId, :lineUserId, :customerName,
                            :startsAt, :endsAt, :startsAt, :status,
                            :idempotencyKey, :createdAt, null
                        )
                        """)
                .param("id", reservation.id())
                .param("tenantId", reservation.tenantId())
                .param("serviceId", reservation.serviceId())
                .param("lineUserId", reservation.lineUserId())
                .param("customerName", reservation.customerName())
                .param("startsAt", utc(reservation.startsAt()))
                .param("endsAt", utc(reservation.endsAt()))
                .param("status", reservation.status())
                .param("idempotencyKey", reservation.idempotencyKey())
                .param("createdAt", utc(reservation.createdAt()))
                .update();
    }

    public void cancel(String tenantId, String reservationId, Instant cancelledAt) {
        jdbc.sql("""
                        update reservations
                        set status = 'CANCELLED', cancelled_at = :cancelledAt,
                            active_slot_key = null
                        where tenant_id = :tenantId and id = :reservationId
                        """)
                .param("cancelledAt", utc(cancelledAt))
                .param("tenantId", tenantId)
                .param("reservationId", reservationId)
                .update();
    }

    private ReservationRead mapReservation(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime cancelled = rs.getObject("cancelled_at", OffsetDateTime.class);
        return new ReservationRead(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("service_id"),
                rs.getString("line_user_id"),
                rs.getString("customer_name"),
                rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                rs.getObject("ends_at", OffsetDateTime.class).toInstant(),
                rs.getString("status"),
                rs.getString("idempotency_key"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                cancelled == null ? null : cancelled.toInstant());
    }

    private OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
