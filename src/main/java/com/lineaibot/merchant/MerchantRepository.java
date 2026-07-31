package com.lineaibot.merchant;

import static com.lineaibot.merchant.MerchantDtos.StaffView;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MerchantRepository {

    public record StaffSecretRow(StaffView staff, String lineUserIdEncrypted) {}

    public record StaffLinkRow(
            String id,
            String tenantId,
            String displayName,
            String role,
            Instant expiresAt,
            Instant consumedAt) {}

    public record ManageTokenRow(
            String id,
            String tenantId,
            String staffId,
            String purpose,
            Instant expiresAt,
            Instant consumedAt) {}

    public record DailySummaryCandidate(
            String tenantId,
            String tenantName,
            String tenantSlug,
            String timezone,
            StaffView staff,
            String lineUserIdEncrypted) {}

    private final JdbcClient jdbc;

    public MerchantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertStaffLink(
            String id,
            String tenantId,
            String tokenHash,
            String displayName,
            String role,
            Instant expiresAt,
            Instant createdAt) {
        jdbc.sql("""
                        insert into merchant_staff_link_tokens (
                            id, tenant_id, token_hash, display_name, role,
                            expires_at, consumed_at, created_at
                        ) values (
                            :id, :tenantId, :tokenHash, :displayName, :role,
                            :expiresAt, null, :createdAt
                        )
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("tokenHash", tokenHash)
                .param("displayName", displayName)
                .param("role", role)
                .param("expiresAt", utc(expiresAt))
                .param("createdAt", utc(createdAt))
                .update();
    }

    public Optional<StaffLinkRow> findUsableStaffLink(
            String tenantId, String tokenHash, Instant now) {
        return jdbc.sql("""
                        select * from merchant_staff_link_tokens
                        where tenant_id = :tenantId
                          and token_hash = :tokenHash
                          and consumed_at is null
                          and expires_at >= :now
                        """)
                .param("tenantId", tenantId)
                .param("tokenHash", tokenHash)
                .param("now", utc(now))
                .query((rs, rowNum) -> new StaffLinkRow(
                        rs.getString("id"),
                        rs.getString("tenant_id"),
                        rs.getString("display_name"),
                        rs.getString("role"),
                        instant(rs, "expires_at"),
                        nullableInstant(rs, "consumed_at")))
                .optional();
    }

    public boolean consumeStaffLink(String id, Instant consumedAt) {
        return jdbc.sql("""
                        update merchant_staff_link_tokens
                        set consumed_at = :consumedAt
                        where id = :id and consumed_at is null
                        """)
                        .param("consumedAt", utc(consumedAt))
                        .param("id", id)
                        .update()
                == 1;
    }

    public StaffView insertStaff(
            String id,
            String tenantId,
            String lineUserKey,
            String lineUserIdEncrypted,
            String displayName,
            String role,
            Instant now) {
        jdbc.sql("""
                        insert into merchant_staff (
                            id, tenant_id, line_user_key, line_user_id_encrypted,
                            display_name, role, status, notify_new_booking,
                            notify_cancellation, daily_summary_enabled,
                            daily_summary_time, created_at, updated_at
                        ) values (
                            :id, :tenantId, :lineUserKey, :lineUserIdEncrypted,
                            :displayName, :role, 'ACTIVE', true,
                            true, false, :dailySummaryTime, :now, :now
                        )
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("lineUserKey", lineUserKey)
                .param("lineUserIdEncrypted", lineUserIdEncrypted)
                .param("displayName", displayName)
                .param("role", role)
                .param("dailySummaryTime", LocalTime.of(8, 0))
                .param("now", utc(now))
                .update();
        return findStaff(tenantId, id).orElseThrow();
    }

    public Optional<StaffView> findActiveStaffByLineKey(String tenantId, String lineUserKey) {
        return jdbc.sql("""
                        select * from merchant_staff
                        where tenant_id = :tenantId
                          and line_user_key = :lineUserKey
                          and status = 'ACTIVE'
                        """)
                .param("tenantId", tenantId)
                .param("lineUserKey", lineUserKey)
                .query(this::mapStaff)
                .optional();
    }

    public Optional<StaffView> findStaffByLineKey(String tenantId, String lineUserKey) {
        return jdbc.sql("""
                        select * from merchant_staff
                        where tenant_id = :tenantId
                          and line_user_key = :lineUserKey
                        """)
                .param("tenantId", tenantId)
                .param("lineUserKey", lineUserKey)
                .query(this::mapStaff)
                .optional();
    }

    public Optional<StaffView> findStaff(String tenantId, String staffId) {
        return jdbc.sql("""
                        select * from merchant_staff
                        where tenant_id = :tenantId and id = :staffId
                        """)
                .param("tenantId", tenantId)
                .param("staffId", staffId)
                .query(this::mapStaff)
                .optional();
    }

    public List<StaffView> listStaff(String tenantId) {
        return jdbc.sql("""
                        select * from merchant_staff
                        where tenant_id = :tenantId
                          and status = 'ACTIVE'
                        order by created_at
                        """)
                .param("tenantId", tenantId)
                .query(this::mapStaff)
                .list();
    }

    public int countActiveOwners(String tenantId) {
        return jdbc.sql("""
                        select count(*) from merchant_staff
                        where tenant_id = :tenantId
                          and role = 'OWNER'
                          and status = 'ACTIVE'
                        """)
                .param("tenantId", tenantId)
                .query(Integer.class)
                .single();
    }

    public List<StaffSecretRow> listNotificationStaff(
            String tenantId, String eventType) {
        String preferenceColumn = "RESERVATION_CREATED".equals(eventType)
                ? "notify_new_booking"
                : "notify_cancellation";
        return jdbc.sql("""
                        select * from merchant_staff
                        where tenant_id = :tenantId
                          and status = 'ACTIVE'
                          and %s = true
                        order by created_at
                        """
                        .formatted(preferenceColumn))
                .param("tenantId", tenantId)
                .query((rs, rowNum) -> new StaffSecretRow(
                        mapStaff(rs, rowNum), rs.getString("line_user_id_encrypted")))
                .list();
    }

    public StaffView updateStaff(
            String tenantId,
            String staffId,
            String displayName,
            String role,
            String status,
            boolean notifyNewBooking,
            boolean notifyCancellation,
            boolean dailySummaryEnabled,
            LocalTime dailySummaryTime,
            Instant updatedAt) {
        jdbc.sql("""
                        update merchant_staff
                        set display_name = :displayName,
                            role = :role,
                            status = :status,
                            notify_new_booking = :notifyNewBooking,
                            notify_cancellation = :notifyCancellation,
                            daily_summary_enabled = :dailySummaryEnabled,
                            daily_summary_time = :dailySummaryTime,
                            updated_at = :updatedAt
                        where tenant_id = :tenantId and id = :staffId
                        """)
                .param("displayName", displayName)
                .param("role", role)
                .param("status", status)
                .param("notifyNewBooking", notifyNewBooking)
                .param("notifyCancellation", notifyCancellation)
                .param("dailySummaryEnabled", dailySummaryEnabled)
                .param("dailySummaryTime", dailySummaryTime)
                .param("updatedAt", utc(updatedAt))
                .param("tenantId", tenantId)
                .param("staffId", staffId)
                .update();
        return findStaff(tenantId, staffId).orElseThrow();
    }

    public StaffView reactivateStaff(
            String tenantId,
            String staffId,
            String lineUserIdEncrypted,
            String displayName,
            String role,
            Instant updatedAt) {
        jdbc.sql("""
                        update merchant_staff
                        set line_user_id_encrypted = :lineUserIdEncrypted,
                            display_name = :displayName,
                            role = :role,
                            status = 'ACTIVE',
                            notify_new_booking = true,
                            notify_cancellation = true,
                            daily_summary_enabled = false,
                            daily_summary_time = :dailySummaryTime,
                            updated_at = :updatedAt
                        where tenant_id = :tenantId
                          and id = :staffId
                          and status <> 'ACTIVE'
                        """)
                .param("lineUserIdEncrypted", lineUserIdEncrypted)
                .param("displayName", displayName)
                .param("role", role)
                .param("dailySummaryTime", LocalTime.of(8, 0))
                .param("updatedAt", utc(updatedAt))
                .param("tenantId", tenantId)
                .param("staffId", staffId)
                .update();
        return findStaff(tenantId, staffId).orElseThrow();
    }

    public StaffView disableStaff(String tenantId, String staffId, Instant updatedAt) {
        jdbc.sql("""
                        update merchant_staff
                        set status = 'DISABLED',
                            updated_at = :updatedAt
                        where tenant_id = :tenantId
                          and id = :staffId
                          and status = 'ACTIVE'
                        """)
                .param("updatedAt", utc(updatedAt))
                .param("tenantId", tenantId)
                .param("staffId", staffId)
                .update();
        return findStaff(tenantId, staffId).orElseThrow();
    }

    public void insertManageToken(
            String id,
            String tenantId,
            String staffId,
            String tokenHash,
            String purpose,
            Instant expiresAt,
            Instant createdAt) {
        jdbc.sql("""
                        insert into merchant_manage_tokens (
                            id, tenant_id, staff_id, token_hash, purpose,
                            expires_at, consumed_at, created_at
                        ) values (
                            :id, :tenantId, :staffId, :tokenHash, :purpose,
                            :expiresAt, null, :createdAt
                        )
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("staffId", staffId)
                .param("tokenHash", tokenHash)
                .param("purpose", purpose)
                .param("expiresAt", utc(expiresAt))
                .param("createdAt", utc(createdAt))
                .update();
    }

    public Optional<ManageTokenRow> findUsableManageToken(
            String tokenHash, String purpose, Instant now) {
        return jdbc.sql("""
                        select * from merchant_manage_tokens
                        where token_hash = :tokenHash
                          and purpose = :purpose
                          and consumed_at is null
                          and expires_at >= :now
                        """)
                .param("tokenHash", tokenHash)
                .param("purpose", purpose)
                .param("now", utc(now))
                .query((rs, rowNum) -> new ManageTokenRow(
                        rs.getString("id"),
                        rs.getString("tenant_id"),
                        rs.getString("staff_id"),
                        rs.getString("purpose"),
                        instant(rs, "expires_at"),
                        nullableInstant(rs, "consumed_at")))
                .optional();
    }

    public boolean consumeManageToken(String id, Instant consumedAt) {
        return jdbc.sql("""
                        update merchant_manage_tokens
                        set consumed_at = :consumedAt
                        where id = :id and consumed_at is null
                        """)
                        .param("consumedAt", utc(consumedAt))
                        .param("id", id)
                        .update()
                == 1;
    }

    public List<DailySummaryCandidate> listDailySummaryCandidates() {
        return jdbc.sql("""
                        select s.*, t.name as tenant_name, t.slug as tenant_slug,
                               t.timezone as tenant_timezone
                        from merchant_staff s
                        join tenants t on t.id = s.tenant_id
                        where s.status = 'ACTIVE'
                          and s.daily_summary_enabled = true
                          and t.active = true
                        """)
                .query((rs, rowNum) -> new DailySummaryCandidate(
                        rs.getString("tenant_id"),
                        rs.getString("tenant_name"),
                        rs.getString("tenant_slug"),
                        rs.getString("tenant_timezone"),
                        mapStaff(rs, rowNum),
                        rs.getString("line_user_id_encrypted")))
                .list();
    }

    public boolean claimDailySummary(
            String tenantId, String staffId, LocalDate localDate, Instant now) {
        try {
            jdbc.sql("""
                            insert into merchant_daily_summary_runs (
                                id, tenant_id, staff_id, local_date, created_at
                            ) values (
                                :id, :tenantId, :staffId, :localDate, :createdAt
                            )
                            """)
                    .param("id", UUID.randomUUID().toString())
                    .param("tenantId", tenantId)
                    .param("staffId", staffId)
                    .param("localDate", localDate)
                    .param("createdAt", utc(now))
                    .update();
            return true;
        } catch (DataIntegrityViolationException exception) {
            return false;
        }
    }

    public void releaseDailySummaryClaim(
            String tenantId, String staffId, LocalDate localDate) {
        jdbc.sql("""
                        delete from merchant_daily_summary_runs
                        where tenant_id = :tenantId
                          and staff_id = :staffId
                          and local_date = :localDate
                        """)
                .param("tenantId", tenantId)
                .param("staffId", staffId)
                .param("localDate", localDate)
                .update();
    }

    private StaffView mapStaff(ResultSet rs, int rowNum) throws SQLException {
        return new StaffView(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("display_name"),
                rs.getString("role"),
                rs.getString("status"),
                rs.getBoolean("notify_new_booking"),
                rs.getBoolean("notify_cancellation"),
                rs.getBoolean("daily_summary_enabled"),
                rs.getObject("daily_summary_time", LocalTime.class),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
