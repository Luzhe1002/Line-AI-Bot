package com.lineaibot.tenant;

import static com.lineaibot.tenant.TenantDtos.BookingServiceRead;
import static com.lineaibot.tenant.TenantDtos.BookingAddOnRead;
import static com.lineaibot.tenant.TenantDtos.BusinessHourRead;
import static com.lineaibot.tenant.TenantDtos.TenantRead;

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
public class TenantRepository {

    public record TenantRow(
            String id,
            String slug,
            String name,
            String timezone,
            int slotMinutes,
            String adminApiKeyHash,
            boolean active,
            Instant createdAt) {

        public TenantRead toRead() {
            return new TenantRead(id, name, slug, timezone, slotMinutes, active, createdAt);
        }
    }

    public record LineChannelRow(
            String id,
            String tenantId,
            String channelSecretEncrypted,
            String channelAccessTokenEncrypted,
            boolean enabled) {}

    private final JdbcClient jdbc;

    public TenantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TenantRow> findById(String id) {
        return jdbc.sql("""
                        select id, slug, name, timezone, slot_minutes, admin_api_key_hash,
                               active, created_at
                        from tenants where id = :id
                        """)
                .param("id", id)
                .query(this::mapTenant)
                .optional();
    }

    public Optional<TenantRow> findActiveBySlug(String slug) {
        return jdbc.sql("""
                        select id, slug, name, timezone, slot_minutes, admin_api_key_hash,
                               active, created_at
                        from tenants where slug = :slug and active = true
                        """)
                .param("slug", slug)
                .query(this::mapTenant)
                .optional();
    }

    public List<TenantRead> findAll() {
        return jdbc.sql("""
                        select id, slug, name, timezone, slot_minutes, admin_api_key_hash,
                               active, created_at
                        from tenants order by created_at
                        """)
                .query((rs, rowNum) -> mapTenant(rs, rowNum).toRead())
                .list();
    }

    public void insertTenant(TenantRow tenant) {
        jdbc.sql("""
                        insert into tenants (
                            id, slug, name, timezone, slot_minutes, admin_api_key_hash,
                            active, created_at, updated_at
                        ) values (
                            :id, :slug, :name, :timezone, :slotMinutes, :hash,
                            :active, :createdAt, :createdAt
                        )
                        """)
                .param("id", tenant.id())
                .param("slug", tenant.slug())
                .param("name", tenant.name())
                .param("timezone", tenant.timezone())
                .param("slotMinutes", tenant.slotMinutes())
                .param("hash", tenant.adminApiKeyHash())
                .param("active", tenant.active())
                .param("createdAt", utc(tenant.createdAt()))
                .update();
    }

    public void insertDefaultBusinessHour(
            String id, String tenantId, int weekday, LocalTime open, LocalTime close) {
        jdbc.sql("""
                        insert into business_hours (
                            id, tenant_id, weekday, open_time, close_time, active
                        ) values (:id, :tenantId, :weekday, :open, :close, true)
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("weekday", weekday)
                .param("open", open)
                .param("close", close)
                .update();
    }

    public void insertDefaultBookingService(String id, String tenantId, Instant createdAt) {
        jdbc.sql("""
                        insert into booking_services (
                            id, tenant_id, name, description, duration_minutes,
                            price_amount, active, created_at, updated_at
                        ) values (
                            :id, :tenantId, '一般預約', '預設的一對一預約服務',
                            (select slot_minutes from tenants where id = :tenantId),
                            0, true, :createdAt, :createdAt
                        )
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("createdAt", utc(createdAt))
                .update();
    }

    public void insertDefaultDataset(String id, String tenantId, Instant createdAt) {
        jdbc.sql("""
                        insert into datasets (
                            id, tenant_id, name, version, status, active_marker, created_at
                        ) values (
                            :id, :tenantId, '客服知識庫', 1, 'DRAFT', null, :createdAt
                        )
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("createdAt", utc(createdAt))
                .update();
    }

    public Optional<LineChannelRow> findLineChannel(String tenantId) {
        return jdbc.sql("""
                        select id, tenant_id, channel_secret_encrypted,
                               channel_access_token_encrypted, enabled
                        from line_channels where tenant_id = :tenantId
                        """)
                .param("tenantId", tenantId)
                .query((rs, rowNum) -> new LineChannelRow(
                        rs.getString("id"),
                        rs.getString("tenant_id"),
                        rs.getString("channel_secret_encrypted"),
                        rs.getString("channel_access_token_encrypted"),
                        rs.getBoolean("enabled")))
                .optional();
    }

    public void saveLineChannel(
            String tenantId,
            String channelSecretEncrypted,
            String accessTokenEncrypted,
            boolean enabled,
            Instant now) {
        int updated = jdbc.sql("""
                        update line_channels
                        set channel_secret_encrypted = :secret,
                            channel_access_token_encrypted = :token,
                            enabled = :enabled,
                            updated_at = :now
                        where tenant_id = :tenantId
                        """)
                .param("secret", channelSecretEncrypted)
                .param("token", accessTokenEncrypted)
                .param("enabled", enabled)
                .param("now", utc(now))
                .param("tenantId", tenantId)
                .update();
        if (updated == 0) {
            jdbc.sql("""
                            insert into line_channels (
                                id, tenant_id, channel_secret_encrypted,
                                channel_access_token_encrypted, enabled, created_at, updated_at
                            ) values (
                                :id, :tenantId, :secret, :token, :enabled, :now, :now
                            )
                            """)
                    .param("id", java.util.UUID.randomUUID().toString())
                    .param("tenantId", tenantId)
                    .param("secret", channelSecretEncrypted)
                    .param("token", accessTokenEncrypted)
                    .param("enabled", enabled)
                    .param("now", utc(now))
                    .update();
        }
    }

    public List<BusinessHourRead> findBusinessHours(String tenantId) {
        return jdbc.sql("""
                        select id, tenant_id, weekday, open_time, close_time, active
                        from business_hours where tenant_id = :tenantId order by weekday
                        """)
                .param("tenantId", tenantId)
                .query((rs, rowNum) -> new BusinessHourRead(
                        rs.getString("id"),
                        rs.getString("tenant_id"),
                        rs.getInt("weekday"),
                        rs.getObject("open_time", LocalTime.class),
                        rs.getObject("close_time", LocalTime.class),
                        rs.getBoolean("active")))
                .list();
    }

    public BusinessHourRead saveBusinessHour(
            String tenantId,
            int weekday,
            LocalTime open,
            LocalTime close,
            boolean active) {
        var existing = jdbc.sql("""
                        select id from business_hours
                        where tenant_id = :tenantId and weekday = :weekday
                        """)
                .param("tenantId", tenantId)
                .param("weekday", weekday)
                .query(String.class)
                .optional();
        String id = existing.orElseGet(() -> java.util.UUID.randomUUID().toString());
        if (existing.isPresent()) {
            jdbc.sql("""
                            update business_hours
                            set open_time = :open, close_time = :close, active = :active
                            where id = :id and tenant_id = :tenantId
                            """)
                    .param("open", open)
                    .param("close", close)
                    .param("active", active)
                    .param("id", id)
                    .param("tenantId", tenantId)
                    .update();
        } else {
            jdbc.sql("""
                            insert into business_hours (
                                id, tenant_id, weekday, open_time, close_time, active
                            ) values (:id, :tenantId, :weekday, :open, :close, :active)
                            """)
                    .param("id", id)
                    .param("tenantId", tenantId)
                    .param("weekday", weekday)
                    .param("open", open)
                    .param("close", close)
                    .param("active", active)
                    .update();
        }
        return new BusinessHourRead(id, tenantId, weekday, open, close, active);
    }

    public BookingServiceRead insertBookingService(
            String tenantId,
            String name,
            String description,
            int durationMinutes,
            int priceAmount,
            List<String> addOnIds,
            Instant now) {
        String id = java.util.UUID.randomUUID().toString();
        jdbc.sql("""
                        insert into booking_services (
                            id, tenant_id, name, description, duration_minutes,
                            price_amount, active, created_at, updated_at
                        ) values (
                            :id, :tenantId, :name, :description, :durationMinutes,
                            :priceAmount, true, :createdAt, :createdAt
                        )
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("name", name)
                .param("description", description)
                .param("durationMinutes", durationMinutes)
                .param("priceAmount", priceAmount)
                .param("createdAt", utc(now))
                .update();
        replaceBookingServiceAddOns(tenantId, id, addOnIds, now);
        return findBookingService(tenantId, id).orElseThrow();
    }

    public BookingServiceRead updateBookingService(
            String tenantId,
            String serviceId,
            String name,
            String description,
            int durationMinutes,
            int priceAmount,
            boolean active,
            List<String> addOnIds,
            Instant now) {
        jdbc.sql("""
                        update booking_services
                        set name = :name,
                            description = :description,
                            duration_minutes = :durationMinutes,
                            price_amount = :priceAmount,
                            active = :active,
                            updated_at = :updatedAt
                        where id = :serviceId and tenant_id = :tenantId
                        """)
                .param("name", name)
                .param("description", description)
                .param("durationMinutes", durationMinutes)
                .param("priceAmount", priceAmount)
                .param("active", active)
                .param("updatedAt", utc(now))
                .param("serviceId", serviceId)
                .param("tenantId", tenantId)
                .update();
        replaceBookingServiceAddOns(tenantId, serviceId, addOnIds, now);
        return findBookingService(tenantId, serviceId).orElseThrow();
    }

    public Optional<BookingServiceRead> findBookingService(String tenantId, String serviceId) {
        return jdbc.sql("""
                        select id, tenant_id, name, description, duration_minutes,
                               price_amount, active
                        from booking_services
                        where id = :serviceId and tenant_id = :tenantId
                        """)
                .param("serviceId", serviceId)
                .param("tenantId", tenantId)
                .query((rs, rowNum) -> mapBookingService(rs))
                .optional()
                .map(this::withAddOns);
    }

    public List<BookingServiceRead> findBookingServices(String tenantId) {
        return jdbc.sql("""
                        select id, tenant_id, name, description, duration_minutes,
                               price_amount, active
                        from booking_services
                        where tenant_id = :tenantId order by created_at
                        """)
                .param("tenantId", tenantId)
                .query((rs, rowNum) -> mapBookingService(rs))
                .list()
                .stream()
                .map(this::withAddOns)
                .toList();
    }

    public BookingAddOnRead insertBookingAddOn(
            String tenantId,
            String name,
            String description,
            int durationMinutes,
            int priceAmount,
            Instant now) {
        String id = java.util.UUID.randomUUID().toString();
        jdbc.sql("""
                        insert into booking_add_ons (
                            id, tenant_id, name, description, duration_minutes,
                            price_amount, active, created_at, updated_at
                        ) values (
                            :id, :tenantId, :name, :description, :durationMinutes,
                            :priceAmount, true, :createdAt, :createdAt
                        )
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("name", name)
                .param("description", description)
                .param("durationMinutes", durationMinutes)
                .param("priceAmount", priceAmount)
                .param("createdAt", utc(now))
                .update();
        return findBookingAddOn(tenantId, id).orElseThrow();
    }

    public BookingAddOnRead updateBookingAddOn(
            String tenantId,
            String addOnId,
            String name,
            String description,
            int durationMinutes,
            int priceAmount,
            boolean active,
            Instant now) {
        jdbc.sql("""
                        update booking_add_ons
                        set name = :name,
                            description = :description,
                            duration_minutes = :durationMinutes,
                            price_amount = :priceAmount,
                            active = :active,
                            updated_at = :updatedAt
                        where id = :addOnId and tenant_id = :tenantId
                        """)
                .param("name", name)
                .param("description", description)
                .param("durationMinutes", durationMinutes)
                .param("priceAmount", priceAmount)
                .param("active", active)
                .param("updatedAt", utc(now))
                .param("addOnId", addOnId)
                .param("tenantId", tenantId)
                .update();
        return findBookingAddOn(tenantId, addOnId).orElseThrow();
    }

    public Optional<BookingAddOnRead> findBookingAddOn(String tenantId, String addOnId) {
        return jdbc.sql("""
                        select id, tenant_id, name, description, duration_minutes,
                               price_amount, active
                        from booking_add_ons
                        where id = :addOnId and tenant_id = :tenantId
                        """)
                .param("addOnId", addOnId)
                .param("tenantId", tenantId)
                .query((rs, rowNum) -> mapBookingAddOn(rs))
                .optional();
    }

    public List<BookingAddOnRead> findBookingAddOns(String tenantId) {
        return jdbc.sql("""
                        select id, tenant_id, name, description, duration_minutes,
                               price_amount, active
                        from booking_add_ons
                        where tenant_id = :tenantId
                        order by created_at
                        """)
                .param("tenantId", tenantId)
                .query((rs, rowNum) -> mapBookingAddOn(rs))
                .list();
    }

    public Optional<String> findAddOnService(String tenantId, String addOnId) {
        return jdbc.sql("select service_id from booking_service_add_ons where tenant_id = :tenant and add_on_id = :id")
                .param("tenant", tenantId).param("id", addOnId).query(String.class).optional();
    }

    public void lockBookingService(String tenantId, String serviceId) {
        jdbc.sql("select id from booking_services where tenant_id = :tenant and id = :id for update")
                .param("tenant", tenantId).param("id", serviceId).query(String.class).optional();
    }

    public void replaceBookingServiceAddOns(
            String tenantId, String serviceId, List<String> addOnIds, Instant now) {
        jdbc.sql("""
                        delete from booking_service_add_ons
                        where tenant_id = :tenantId and service_id = :serviceId
                        """)
                .param("tenantId", tenantId)
                .param("serviceId", serviceId)
                .update();
        for (String addOnId : addOnIds) {
            jdbc.sql("""
                            insert into booking_service_add_ons (
                                tenant_id, service_id, add_on_id, created_at
                            ) values (:tenantId, :serviceId, :addOnId, :createdAt)
                            """)
                    .param("tenantId", tenantId)
                    .param("serviceId", serviceId)
                    .param("addOnId", addOnId)
                    .param("createdAt", utc(now))
                    .update();
        }
    }

    private BookingServiceRead withAddOns(BookingServiceRead service) {
        List<BookingAddOnRead> addOns = jdbc.sql("""
                        select a.id, a.tenant_id, a.name, a.description,
                               a.duration_minutes, a.price_amount, a.active
                        from booking_service_add_ons sa
                        join booking_add_ons a
                          on a.id = sa.add_on_id and a.tenant_id = sa.tenant_id
                        where sa.tenant_id = :tenantId and sa.service_id = :serviceId
                        order by a.created_at
                        """)
                .param("tenantId", service.tenantId())
                .param("serviceId", service.id())
                .query((rs, rowNum) -> mapBookingAddOn(rs))
                .list();
        return new BookingServiceRead(
                service.id(),
                service.tenantId(),
                service.name(),
                service.description(),
                service.durationMinutes(),
                service.priceAmount(),
                service.active(),
                addOns);
    }

    private BookingServiceRead mapBookingService(ResultSet rs) throws SQLException {
        return new BookingServiceRead(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getInt("duration_minutes"),
                rs.getInt("price_amount"),
                rs.getBoolean("active"),
                List.of());
    }

    private BookingAddOnRead mapBookingAddOn(ResultSet rs) throws SQLException {
        return new BookingAddOnRead(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getInt("duration_minutes"),
                rs.getInt("price_amount"),
                rs.getBoolean("active"));
    }

    private TenantRow mapTenant(ResultSet rs, int rowNum) throws SQLException {
        return new TenantRow(
                rs.getString("id"),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("timezone"),
                rs.getInt("slot_minutes"),
                rs.getString("admin_api_key_hash"),
                rs.getBoolean("active"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
