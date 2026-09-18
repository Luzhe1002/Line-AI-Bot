package com.lineaibot.merchant;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MerchantRichMenuRepository {

    public record CustomerMenuJob(String tenantId, int revision) {}

    public void requestCustomerSync(String tenantId) {
        jdbc.sql("select id from tenants where id = :id for update").param("id", tenantId).query(String.class).single();
        int changed = jdbc.sql("update customer_menu_sync set revision = revision + 1, next_attempt_at = current_timestamp where tenant_id = :id")
                .param("id", tenantId).update();
        if (changed == 0) {
            jdbc.sql("insert into customer_menu_sync (tenant_id) values (:id)").param("id", tenantId).update();
        }
    }

    public List<CustomerMenuJob> readyCustomerMenus(Instant now) {
        return jdbc.sql("""
                select sync.tenant_id, sync.revision from customer_menu_sync sync
                join tenants tenant on tenant.id = sync.tenant_id and tenant.active = true
                join line_channels channel on channel.tenant_id = sync.tenant_id and channel.enabled = true
                where sync.synced_revision < sync.revision and sync.next_attempt_at <= :now
                  and (sync.locked_until is null or sync.locked_until < :now)
                order by sync.next_attempt_at limit 10
                """).param("now", utc(now))
                .query((rs, n) -> new CustomerMenuJob(rs.getString("tenant_id"), rs.getInt("revision"))).list();
    }

    public boolean claimCustomerMenu(CustomerMenuJob job, Instant now) {
        return jdbc.sql("""
                update customer_menu_sync set locked_until = :until
                where tenant_id = :id and revision = :revision
                  and (locked_until is null or locked_until < :now)
                """).param("id", job.tenantId()).param("revision", job.revision())
                .param("now", utc(now)).param("until", utc(now.plusSeconds(120))).update() == 1;
    }

    public void finishCustomerMenu(CustomerMenuJob job, boolean success, Instant now) {
        jdbc.sql("""
                update customer_menu_sync
                set synced_revision = case when :success then greatest(synced_revision, :revision) else synced_revision end,
                    locked_until = null, next_attempt_at = :next
                where tenant_id = :id
                """).param("id", job.tenantId()).param("revision", job.revision())
                .param("success", success).param("next", utc(success ? now : now.plusSeconds(60))).update();
    }

    public record SyncJob(
            String staffId,
            String tenantId,
            String desiredRole,
            boolean desiredLinked,
            int revision,
            int attempts,
            String lineUserIdEncrypted) {}

    public record RichMenuRow(
            String id,
            String tenantId,
            String role,
            String lineRichMenuId,
            String status) {}

    private final JdbcClient jdbc;

    public MerchantRichMenuRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void requestSync(
            String tenantId,
            String staffId,
            String role,
            boolean linked,
            Instant now) {
        int updated = updateDesiredState(tenantId, staffId, role, linked, now);
        if (updated == 1) {
            return;
        }
        try {
            jdbc.sql("""
                            insert into merchant_rich_menu_sync (
                                staff_id, tenant_id, desired_role, desired_linked,
                                revision, status, attempts, next_attempt_at,
                                locked_at, last_error, updated_at
                            ) values (
                                :staffId, :tenantId, :role, :linked,
                                1, 'READY', 0, :now, null, null, :now
                            )
                            """)
                    .param("staffId", staffId)
                    .param("tenantId", tenantId)
                    .param("role", role)
                    .param("linked", linked)
                    .param("now", utc(now))
                    .update();
        } catch (DataIntegrityViolationException exception) {
            if (updateDesiredState(tenantId, staffId, role, linked, now) != 1) {
                throw exception;
            }
        }
    }

    public void requestTenantSync(String tenantId, Instant now) {
        var desiredStates = jdbc.sql("""
                        select id, role, status
                        from merchant_staff
                        where tenant_id = :tenantId
                        """)
                .param("tenantId", tenantId)
                .query((rs, rowNum) -> new DesiredState(
                        rs.getString("id"),
                        rs.getString("role"),
                        "ACTIVE".equals(rs.getString("status"))))
                .list();
        desiredStates.forEach(state ->
                requestSync(tenantId, state.staffId(), state.role(), state.linked(), now));
    }

    public void recoverStaleJobs(Instant staleBefore, Instant now) {
        jdbc.sql("""
                        update merchant_rich_menu_sync
                        set status = 'RETRY',
                            next_attempt_at = :now,
                            locked_at = null,
                            last_error = 'Recovered stale rich menu synchronization',
                            updated_at = :now
                        where status = 'PROCESSING'
                          and locked_at < :staleBefore
                        """)
                .param("now", utc(now))
                .param("staleBefore", utc(staleBefore))
                .update();
    }

    public List<String> findReadyStaffIds(Instant now, int limit) {
        return jdbc.sql("""
                        select staff_id
                        from merchant_rich_menu_sync
                        where status in ('READY', 'RETRY')
                          and next_attempt_at <= :now
                        order by next_attempt_at, updated_at
                        limit :limit
                        """)
                .param("now", utc(now))
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    public boolean claim(String staffId, Instant now) {
        return jdbc.sql("""
                                update merchant_rich_menu_sync
                                set status = 'PROCESSING',
                                    locked_at = :now,
                                    updated_at = :now
                                where staff_id = :staffId
                                  and status in ('READY', 'RETRY')
                                  and next_attempt_at <= :now
                                """)
                        .param("staffId", staffId)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    public Optional<SyncJob> findClaimedJob(String staffId) {
        return jdbc.sql("""
                        select sync.*, staff.line_user_id_encrypted
                        from merchant_rich_menu_sync sync
                        join merchant_staff staff
                          on staff.id = sync.staff_id
                         and staff.tenant_id = sync.tenant_id
                        where sync.staff_id = :staffId
                          and sync.status = 'PROCESSING'
                        """)
                .param("staffId", staffId)
                .query(this::mapSyncJob)
                .optional();
    }

    public void markSynced(String staffId, int revision, Instant now) {
        jdbc.sql("""
                        update merchant_rich_menu_sync
                        set status = 'SYNCED',
                            attempts = 0,
                            locked_at = null,
                            last_error = null,
                            updated_at = :now
                        where staff_id = :staffId
                          and revision = :revision
                          and status = 'PROCESSING'
                        """)
                .param("staffId", staffId)
                .param("revision", revision)
                .param("now", utc(now))
                .update();
    }

    public void markRetry(
            String staffId,
            int revision,
            int attempts,
            Instant nextAttemptAt,
            String error,
            Instant now) {
        jdbc.sql("""
                        update merchant_rich_menu_sync
                        set status = 'RETRY',
                            attempts = :attempts,
                            next_attempt_at = :nextAttemptAt,
                            locked_at = null,
                            last_error = :error,
                            updated_at = :now
                        where staff_id = :staffId
                          and revision = :revision
                          and status = 'PROCESSING'
                        """)
                .param("staffId", staffId)
                .param("revision", revision)
                .param("attempts", attempts)
                .param("nextAttemptAt", utc(nextAttemptAt))
                .param("error", truncate(error))
                .param("now", utc(now))
                .update();
    }

    public Optional<RichMenuRow> findRichMenu(String tenantId, String role) {
        return jdbc.sql("""
                        select *
                        from merchant_rich_menus
                        where tenant_id = :tenantId
                          and role = :role
                        """)
                .param("tenantId", tenantId)
                .param("role", role)
                .query(this::mapRichMenu)
                .optional();
    }

    public RichMenuRow saveRichMenuReference(
            String tenantId,
            String role,
            String lineRichMenuId,
            Instant now) {
        int updated = jdbc.sql("""
                        update merchant_rich_menus
                        set line_rich_menu_id = :lineRichMenuId,
                            status = 'CREATED',
                            last_error = null,
                            updated_at = :now
                        where tenant_id = :tenantId
                          and role = :role
                        """)
                .param("lineRichMenuId", lineRichMenuId)
                .param("now", utc(now))
                .param("tenantId", tenantId)
                .param("role", role)
                .update();
        if (updated == 0) {
            jdbc.sql("""
                            insert into merchant_rich_menus (
                                id, tenant_id, role, line_rich_menu_id,
                                status, last_error, created_at, updated_at
                            ) values (
                                :id, :tenantId, :role, :lineRichMenuId,
                                'CREATED', null, :now, :now
                            )
                            """)
                    .param("id", UUID.randomUUID().toString())
                    .param("tenantId", tenantId)
                    .param("role", role)
                    .param("lineRichMenuId", lineRichMenuId)
                    .param("now", utc(now))
                    .update();
        }
        return findRichMenu(tenantId, role).orElseThrow();
    }

    public void markRichMenuReady(String tenantId, String role, Instant now) {
        jdbc.sql("""
                        update merchant_rich_menus
                        set status = 'READY',
                            last_error = null,
                            updated_at = :now
                        where tenant_id = :tenantId
                          and role = :role
                        """)
                .param("tenantId", tenantId)
                .param("role", role)
                .param("now", utc(now))
                .update();
    }

    public void resetRichMenu(String tenantId, String role, String error, Instant now) {
        jdbc.sql("""
                        update merchant_rich_menus
                        set line_rich_menu_id = null,
                            status = 'PENDING',
                            last_error = :error,
                            updated_at = :now
                        where tenant_id = :tenantId
                          and role = :role
                        """)
                .param("tenantId", tenantId)
                .param("role", role)
                .param("error", truncate(error))
                .param("now", utc(now))
                .update();
    }

    private int updateDesiredState(
            String tenantId,
            String staffId,
            String role,
            boolean linked,
            Instant now) {
        return jdbc.sql("""
                        update merchant_rich_menu_sync
                        set desired_role = :role,
                            desired_linked = :linked,
                            revision = revision + 1,
                            status = 'READY',
                            attempts = 0,
                            next_attempt_at = :now,
                            locked_at = null,
                            last_error = null,
                            updated_at = :now
                        where tenant_id = :tenantId
                          and staff_id = :staffId
                        """)
                .param("role", role)
                .param("linked", linked)
                .param("now", utc(now))
                .param("tenantId", tenantId)
                .param("staffId", staffId)
                .update();
    }

    private SyncJob mapSyncJob(ResultSet rs, int rowNum) throws SQLException {
        return new SyncJob(
                rs.getString("staff_id"),
                rs.getString("tenant_id"),
                rs.getString("desired_role"),
                rs.getBoolean("desired_linked"),
                rs.getInt("revision"),
                rs.getInt("attempts"),
                rs.getString("line_user_id_encrypted"));
    }

    private RichMenuRow mapRichMenu(ResultSet rs, int rowNum) throws SQLException {
        return new RichMenuRow(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("role"),
                rs.getString("line_rich_menu_id"),
                rs.getString("status"));
    }

    private String truncate(String value) {
        String safe = value == null || value.isBlank() ? "Unknown rich menu error" : value;
        return safe.substring(0, Math.min(safe.length(), 1000));
    }

    private OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private record DesiredState(String staffId, String role, boolean linked) {}
}
