package com.lineaibot.booking;

import com.lineaibot.booking.BookingDtos.ReservationRead;
import com.lineaibot.booking.BookingDtos.ReservationAddOnRead;
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
            String id,
            String tenantId,
            String name,
            String description,
            int durationMinutes,
            int priceAmount,
            boolean active,
            List<AddOnRow> addOns) {}

    public record AddOnRow(
            String id,
            String tenantId,
            String name,
            String description,
            int durationMinutes,
            int priceAmount,
            boolean active) {}

    public record BusinessHourRow(
            int weekday, LocalTime openTime, LocalTime closeTime, boolean active) {}

    public record AdminReservationRow(
            String id,
            String serviceId,
            String serviceName,
            List<ReservationAddOnRead> addOns,
            String customerName,
            Instant startsAt,
            Instant endsAt,
            int totalDurationMinutes,
            int totalPriceAmount,
            String status,
            Instant createdAt) {}

    public record BookingBlockRow(
            String id,
            String tenantId,
            Instant startsAt,
            Instant endsAt,
            String reason,
            boolean active,
            String createdByStaffId,
            Instant createdAt,
            Instant releasedAt) {}

    private final JdbcClient jdbc;

    public BookingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ServiceRow> findService(String tenantId, String serviceId) {
        return jdbc.sql("""
                        select id, tenant_id, name, description, duration_minutes,
                               price_amount, active
                        from booking_services
                        where id = :serviceId and tenant_id = :tenantId
                        """)
                .param("serviceId", serviceId)
                .param("tenantId", tenantId)
                .query((rs, rowNum) -> mapService(rs))
                .optional()
                .map(this::withActiveAddOns);
    }

    public List<ServiceRow> findActiveServices(String tenantId) {
        return jdbc.sql("""
                        select id, tenant_id, name, description, duration_minutes,
                               price_amount, active
                        from booking_services
                        where tenant_id = :tenantId and active = true
                        order by created_at
                        """)
                .param("tenantId", tenantId)
                .query((rs, rowNum) -> mapService(rs))
                .list()
                .stream()
                .map(this::withActiveAddOns)
                .toList();
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
                        select starts_at from booking_slot_occupancies
                        where tenant_id = :tenantId
                          and starts_at >= :windowStart
                          and starts_at < :windowEnd
                        """)
                .param("tenantId", tenantId)
                .param("windowStart", utc(windowStart))
                .param("windowEnd", utc(windowEnd))
                .query((rs, rowNum) -> rs.getObject("starts_at", OffsetDateTime.class).toInstant())
                .list();
    }

    public List<AdminReservationRow> findBetween(
            String tenantId, Instant windowStart, Instant windowEnd) {
        return jdbc.sql("""
                        select r.id, r.service_id, r.service_name,
                               r.customer_name, r.starts_at, r.ends_at,
                               r.total_duration_minutes, r.total_price_amount,
                               r.status, r.created_at
                        from reservations r
                        where r.tenant_id = :tenantId
                          and r.starts_at >= :windowStart
                          and r.starts_at < :windowEnd
                        order by r.starts_at, r.created_at
                        """)
                .param("tenantId", tenantId)
                .param("windowStart", utc(windowStart))
                .param("windowEnd", utc(windowEnd))
                .query((rs, rowNum) -> new AdminReservationRow(
                        rs.getString("id"),
                        rs.getString("service_id"),
                        rs.getString("service_name"),
                        List.of(),
                        rs.getString("customer_name"),
                        rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("ends_at", OffsetDateTime.class).toInstant(),
                        rs.getInt("total_duration_minutes"),
                        rs.getInt("total_price_amount"),
                        rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list()
                .stream()
                .map(row -> new AdminReservationRow(
                        row.id(),
                        row.serviceId(),
                        row.serviceName(),
                        findReservationAddOns(tenantId, row.id()),
                        row.customerName(),
                        row.startsAt(),
                        row.endsAt(),
                        row.totalDurationMinutes(),
                        row.totalPriceAmount(),
                        row.status(),
                        row.createdAt()))
                .toList();
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
                .optional()
                .map(this::withReservationAddOns);
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
        return statement.query(this::mapReservation).optional().map(this::withReservationAddOns);
    }

    public List<ReservationRead> findAll(String tenantId) {
        return jdbc.sql("""
                        select * from reservations
                        where tenant_id = :tenantId order by starts_at
                        """)
                .param("tenantId", tenantId)
                .query(this::mapReservation)
                .list()
                .stream()
                .map(this::withReservationAddOns)
                .toList();
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
                .list()
                .stream()
                .map(this::withReservationAddOns)
                .toList();
    }

    public void insert(ReservationRead reservation) {
        jdbc.sql("""
                        insert into reservations (
                            id, tenant_id, service_id, service_name,
                            total_duration_minutes, total_price_amount,
                            line_user_id, customer_name,
                            starts_at, ends_at, active_slot_key, status,
                            idempotency_key, created_at, cancelled_at
                        ) values (
                            :id, :tenantId, :serviceId, :serviceName,
                            :totalDurationMinutes, :totalPriceAmount,
                            :lineUserId, :customerName,
                            :startsAt, :endsAt, :startsAt, :status,
                            :idempotencyKey, :createdAt, null
                        )
                        """)
                .param("id", reservation.id())
                .param("tenantId", reservation.tenantId())
                .param("serviceId", reservation.serviceId())
                .param("serviceName", reservation.serviceName())
                .param("totalDurationMinutes", reservation.totalDurationMinutes())
                .param("totalPriceAmount", reservation.totalPriceAmount())
                .param("lineUserId", reservation.lineUserId())
                .param("customerName", reservation.customerName())
                .param("startsAt", utc(reservation.startsAt()))
                .param("endsAt", utc(reservation.endsAt()))
                .param("status", reservation.status())
                .param("idempotencyKey", reservation.idempotencyKey())
                .param("createdAt", utc(reservation.createdAt()))
                .update();
    }

    public void insertReservationAddOn(
            String tenantId,
            String reservationId,
            ReservationAddOnRead addOn,
            Instant createdAt) {
        jdbc.sql("""
                        insert into reservation_add_ons (
                            id, tenant_id, reservation_id, add_on_id, name,
                            duration_minutes, price_amount, created_at
                        ) values (
                            :id, :tenantId, :reservationId, :addOnId, :name,
                            :durationMinutes, :priceAmount, :createdAt
                        )
                        """)
                .param("id", java.util.UUID.randomUUID().toString())
                .param("tenantId", tenantId)
                .param("reservationId", reservationId)
                .param("addOnId", addOn.id())
                .param("name", addOn.name())
                .param("durationMinutes", addOn.durationMinutes())
                .param("priceAmount", addOn.priceAmount())
                .param("createdAt", utc(createdAt))
                .update();
    }

    public void insertSlotOccupancy(
            String id,
            String tenantId,
            Instant startsAt,
            Instant endsAt,
            String occupancyType,
            String referenceId,
            Instant createdAt) {
        jdbc.sql("""
                        insert into booking_slot_occupancies (
                            id, tenant_id, starts_at, ends_at,
                            occupancy_type, reference_id, created_at
                        ) values (
                            :id, :tenantId, :startsAt, :endsAt,
                            :occupancyType, :referenceId, :createdAt
                        )
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("startsAt", utc(startsAt))
                .param("endsAt", utc(endsAt))
                .param("occupancyType", occupancyType)
                .param("referenceId", referenceId)
                .param("createdAt", utc(createdAt))
                .update();
    }

    public BookingBlockRow insertBlock(
            String id,
            String tenantId,
            Instant startsAt,
            Instant endsAt,
            String reason,
            String staffId,
            Instant createdAt) {
        jdbc.sql("""
                        insert into booking_blocks (
                            id, tenant_id, starts_at, ends_at, reason, active,
                            created_by_staff_id, created_at, released_at
                        ) values (
                            :id, :tenantId, :startsAt, :endsAt, :reason, true,
                            :staffId, :createdAt, null
                        )
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("startsAt", utc(startsAt))
                .param("endsAt", utc(endsAt))
                .param("reason", reason)
                .param("staffId", staffId)
                .param("createdAt", utc(createdAt))
                .update();
        return findBlock(tenantId, id).orElseThrow();
    }

    public Optional<BookingBlockRow> findBlock(String tenantId, String blockId) {
        return jdbc.sql("""
                        select * from booking_blocks
                        where tenant_id = :tenantId and id = :blockId
                        """)
                .param("tenantId", tenantId)
                .param("blockId", blockId)
                .query(this::mapBlock)
                .optional();
    }

    public List<BookingBlockRow> findBlocksBetween(
            String tenantId, Instant windowStart, Instant windowEnd) {
        return jdbc.sql("""
                        select * from booking_blocks
                        where tenant_id = :tenantId
                          and starts_at >= :windowStart
                          and starts_at < :windowEnd
                        order by starts_at
                        """)
                .param("tenantId", tenantId)
                .param("windowStart", utc(windowStart))
                .param("windowEnd", utc(windowEnd))
                .query(this::mapBlock)
                .list();
    }

    public void releaseBlock(String tenantId, String blockId, Instant releasedAt) {
        jdbc.sql("""
                        update booking_blocks
                        set active = false, released_at = :releasedAt
                        where tenant_id = :tenantId and id = :blockId and active = true
                        """)
                .param("releasedAt", utc(releasedAt))
                .param("tenantId", tenantId)
                .param("blockId", blockId)
                .update();
        deleteSlotOccupancy(tenantId, "BLOCK", blockId);
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
        deleteSlotOccupancy(tenantId, "RESERVATION", reservationId);
    }

    public void deleteSlotOccupancy(
            String tenantId, String occupancyType, String referenceId) {
        jdbc.sql("""
                        delete from booking_slot_occupancies
                        where tenant_id = :tenantId
                          and occupancy_type = :occupancyType
                          and reference_id = :referenceId
                        """)
                .param("tenantId", tenantId)
                .param("occupancyType", occupancyType)
                .param("referenceId", referenceId)
                .update();
    }

    private ReservationRead mapReservation(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime cancelled = rs.getObject("cancelled_at", OffsetDateTime.class);
        return new ReservationRead(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("service_id"),
                rs.getString("service_name"),
                List.of(),
                rs.getString("line_user_id"),
                rs.getString("customer_name"),
                rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                rs.getObject("ends_at", OffsetDateTime.class).toInstant(),
                rs.getInt("total_duration_minutes"),
                rs.getInt("total_price_amount"),
                rs.getString("status"),
                rs.getString("idempotency_key"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                cancelled == null ? null : cancelled.toInstant());
    }

    private ServiceRow mapService(ResultSet rs) throws SQLException {
        return new ServiceRow(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getInt("duration_minutes"),
                rs.getInt("price_amount"),
                rs.getBoolean("active"),
                List.of());
    }

    private ServiceRow withActiveAddOns(ServiceRow service) {
        List<AddOnRow> addOns = jdbc.sql("""
                        select a.id, a.tenant_id, a.name, a.description,
                               a.duration_minutes, a.price_amount, a.active
                        from booking_service_add_ons sa
                        join booking_add_ons a
                          on a.id = sa.add_on_id and a.tenant_id = sa.tenant_id
                        where sa.tenant_id = :tenantId
                          and sa.service_id = :serviceId
                          and a.active = true
                        order by a.created_at
                        """)
                .param("tenantId", service.tenantId())
                .param("serviceId", service.id())
                .query((rs, rowNum) -> new AddOnRow(
                        rs.getString("id"),
                        rs.getString("tenant_id"),
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getInt("duration_minutes"),
                        rs.getInt("price_amount"),
                        rs.getBoolean("active")))
                .list();
        return new ServiceRow(
                service.id(),
                service.tenantId(),
                service.name(),
                service.description(),
                service.durationMinutes(),
                service.priceAmount(),
                service.active(),
                addOns);
    }

    private ReservationRead withReservationAddOns(ReservationRead reservation) {
        return new ReservationRead(
                reservation.id(),
                reservation.tenantId(),
                reservation.serviceId(),
                reservation.serviceName(),
                findReservationAddOns(reservation.tenantId(), reservation.id()),
                reservation.lineUserId(),
                reservation.customerName(),
                reservation.startsAt(),
                reservation.endsAt(),
                reservation.totalDurationMinutes(),
                reservation.totalPriceAmount(),
                reservation.status(),
                reservation.idempotencyKey(),
                reservation.createdAt(),
                reservation.cancelledAt());
    }

    private List<ReservationAddOnRead> findReservationAddOns(
            String tenantId, String reservationId) {
        return jdbc.sql("""
                        select add_on_id, name, duration_minutes, price_amount
                        from reservation_add_ons
                        where tenant_id = :tenantId and reservation_id = :reservationId
                        order by created_at
                        """)
                .param("tenantId", tenantId)
                .param("reservationId", reservationId)
                .query((rs, rowNum) -> new ReservationAddOnRead(
                        rs.getString("add_on_id"),
                        rs.getString("name"),
                        rs.getInt("duration_minutes"),
                        rs.getInt("price_amount")))
                .list();
    }

    private BookingBlockRow mapBlock(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime released = rs.getObject("released_at", OffsetDateTime.class);
        return new BookingBlockRow(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                rs.getObject("ends_at", OffsetDateTime.class).toInstant(),
                rs.getString("reason"),
                rs.getBoolean("active"),
                rs.getString("created_by_staff_id"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                released == null ? null : released.toInstant());
    }

    private OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
