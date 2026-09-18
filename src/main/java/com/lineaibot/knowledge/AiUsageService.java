package com.lineaibot.knowledge;

import com.lineaibot.config.AppProperties;
import com.lineaibot.knowledge.AiProvider.TokenUsage;
import com.lineaibot.shared.CryptoService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AiUsageService {

    public record Lease(String id, boolean allowed, String rejectionReason, String userMessage) {

        static Lease allowed(String id) {
            return new Lease(id, true, null, null);
        }

        static Lease rejected(String id, String reason, String message) {
            return new Lease(id, false, reason, message);
        }
    }

    private static final String LIMIT_MESSAGE =
            "AI 詢問次數已達目前上限，請稍後再試或聯絡店家。";
    private static final String DISABLED_MESSAGE =
            "AI 服務目前暫停，請稍後再試或聯絡店家。";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final AppProperties properties;
    private final CryptoService crypto;

    public AiUsageService(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            AppProperties properties,
            CryptoService crypto) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.properties = properties;
        this.crypto = crypto;
    }

    public Lease acquire(
            String tenantId,
            String actorId,
            String source,
            long reservedTokens,
            boolean enforceActorLimits) {
        long reservation = Math.max(1, reservedTokens);
        return transactions.execute(status -> acquireLocked(
                tenantId, actorKey(tenantId, actorId), source, reservation, enforceActorLimits));
    }

    public void succeed(
            Lease lease,
            TokenUsage usage,
            String provider,
            String models,
            String providerRequestIds) {
        if (!lease.allowed()) {
            return;
        }
        TokenUsage safeUsage = usage == null ? TokenUsage.none() : usage;
        transactions.executeWithoutResult(status -> {
            lockControlRow();
            jdbc.sql("""
                            update ai_usage_events
                            set status = 'SUCCEEDED',
                                input_tokens = :inputTokens,
                                cached_input_tokens = :cachedInputTokens,
                                output_tokens = :outputTokens,
                                reasoning_tokens = :reasoningTokens,
                                total_tokens = :totalTokens,
                                provider = :provider,
                                models = :models,
                                provider_request_ids = :requestIds,
                                completed_at = :completedAt
                            where id = :id and status = 'PENDING'
                            """)
                    .param("inputTokens", safeUsage.inputTokens())
                    .param("cachedInputTokens", safeUsage.cachedInputTokens())
                    .param("outputTokens", safeUsage.outputTokens())
                    .param("reasoningTokens", safeUsage.reasoningTokens())
                    .param("totalTokens", safeUsage.totalTokens())
                    .param("provider", trim(provider, 64))
                    .param("models", trim(models, 512))
                    .param("requestIds", trim(providerRequestIds, 1024))
                    .param("completedAt", utc(Instant.now()))
                    .param("id", lease.id())
                    .update();
        });
    }

    public void fail(Lease lease, RuntimeException exception) {
        if (!lease.allowed()) {
            return;
        }
        String error = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
        transactions.executeWithoutResult(status -> {
            lockControlRow();
            jdbc.sql("""
                            update ai_usage_events
                            set status = 'FAILED',
                                total_tokens = reserved_tokens,
                                error = :error,
                                completed_at = :completedAt
                            where id = :id and status = 'PENDING'
                            """)
                    .param("error", trim(error, 2000))
                    .param("completedAt", utc(Instant.now()))
                    .param("id", lease.id())
                    .update();
        });
    }

    private Lease acquireLocked(
            String tenantId,
            String actorKey,
            String source,
            long reservedTokens,
            boolean enforceActorLimits) {
        lockControlRow();
        Instant now = Instant.now();
        expireStaleLeases(now);
        Instant dayStart = now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC)
                .toInstant();

        if (!properties.getAi().isEnabled()) {
            return reject(tenantId, actorKey, source, "GLOBAL_DISABLED", DISABLED_MESSAGE, now);
        }
        if (usedTokens(null, dayStart) + reservedTokens
                > properties.getAi().getGlobalDailyTokenLimit()) {
            return reject(tenantId, actorKey, source, "GLOBAL_DAILY_TOKENS", DISABLED_MESSAGE, now);
        }
        if (usedTokens(tenantId, dayStart) + reservedTokens
                > properties.getAi().getTenantDailyTokenLimit()) {
            return reject(tenantId, actorKey, source, "TENANT_DAILY_TOKENS", LIMIT_MESSAGE, now);
        }
        if (requestCount(tenantId, null, dayStart) >= properties.getAi().getTenantRequestsPerDay()) {
            return reject(tenantId, actorKey, source, "TENANT_DAILY_REQUESTS", LIMIT_MESSAGE, now);
        }
        if (enforceActorLimits
                && requestCount(tenantId, actorKey, now.minus(1, ChronoUnit.MINUTES))
                        >= properties.getAi().getUserRequestsPerMinute()) {
            return reject(tenantId, actorKey, source, "USER_MINUTE_REQUESTS", LIMIT_MESSAGE, now);
        }
        if (enforceActorLimits
                && requestCount(tenantId, actorKey, dayStart)
                        >= properties.getAi().getUserRequestsPerDay()) {
            return reject(tenantId, actorKey, source, "USER_DAILY_REQUESTS", LIMIT_MESSAGE, now);
        }
        if (pendingCount(tenantId) >= properties.getAi().getMaxConcurrentRequestsPerTenant()) {
            return reject(tenantId, actorKey, source, "TENANT_CONCURRENCY", LIMIT_MESSAGE, now);
        }

        String id = UUID.randomUUID().toString();
        insertEvent(id, tenantId, actorKey, source, "PENDING", reservedTokens, null, now);
        return Lease.allowed(id);
    }

    private Lease reject(
            String tenantId,
            String actorKey,
            String source,
            String reason,
            String message,
            Instant now) {
        String id = UUID.randomUUID().toString();
        insertEvent(id, tenantId, actorKey, source, "REJECTED", 0, reason, now);
        return Lease.rejected(id, reason, message);
    }

    private void insertEvent(
            String id,
            String tenantId,
            String actorKey,
            String source,
            String status,
            long reservedTokens,
            String rejectionReason,
            Instant now) {
        jdbc.sql("""
                        insert into ai_usage_events (
                            id, tenant_id, actor_key, source, status, reserved_tokens,
                            input_tokens, cached_input_tokens, output_tokens,
                            reasoning_tokens, total_tokens, rejection_reason,
                            created_at, completed_at
                        ) values (
                            :id, :tenantId, :actorKey, :source, :status, :reservedTokens,
                            0, 0, 0, 0, 0, :rejectionReason,
                            :createdAt, :completedAt
                        )
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("actorKey", actorKey)
                .param("source", trim(source, 32))
                .param("status", status)
                .param("reservedTokens", reservedTokens)
                .param("rejectionReason", rejectionReason)
                .param("createdAt", utc(now))
                .param("completedAt", "REJECTED".equals(status) ? utc(now) : null)
                .update();
    }

    private long usedTokens(String tenantId, Instant since) {
        String tenantFilter = tenantId == null ? "" : " and tenant_id = :tenantId";
        var query = jdbc.sql("""
                        select coalesce(sum(
                            case when status = 'PENDING' then reserved_tokens else total_tokens end
                        ), 0)
                        from ai_usage_events
                        where status in ('PENDING', 'SUCCEEDED', 'FAILED')
                          and created_at >= :since
                        """ + tenantFilter)
                .param("since", utc(since));
        if (tenantId != null) {
            query = query.param("tenantId", tenantId);
        }
        return query.query(Long.class).single();
    }

    private long requestCount(String tenantId, String actorKey, Instant since) {
        String actorFilter = actorKey == null ? "" : " and actor_key = :actorKey";
        var query = jdbc.sql("""
                        select count(*)
                        from ai_usage_events
                        where tenant_id = :tenantId
                          and status in ('PENDING', 'SUCCEEDED', 'FAILED')
                          and created_at >= :since
                        """ + actorFilter)
                .param("tenantId", tenantId)
                .param("since", utc(since));
        if (actorKey != null) {
            query = query.param("actorKey", actorKey);
        }
        return query.query(Long.class).single();
    }

    private long pendingCount(String tenantId) {
        return jdbc.sql("""
                        select count(*) from ai_usage_events
                        where tenant_id = :tenantId and status = 'PENDING'
                        """)
                .param("tenantId", tenantId)
                .query(Long.class)
                .single();
    }

    private void expireStaleLeases(Instant now) {
        jdbc.sql("""
                        update ai_usage_events
                        set status = 'FAILED', total_tokens = reserved_tokens,
                            error = 'AI usage lease expired', completed_at = :now
                        where status = 'PENDING' and created_at < :staleBefore
                        """)
                .param("now", utc(now))
                .param("staleBefore", utc(now.minusSeconds(
                        properties.getAi().getPendingLeaseSeconds())))
                .update();
    }

    private void lockControlRow() {
        jdbc.sql("update ai_usage_control set updated_at = updated_at where id = 1").update();
    }

    private String actorKey(String tenantId, String actorId) {
        String subject = tenantId + ":" + (actorId == null || actorId.isBlank()
                ? "anonymous"
                : actorId);
        return crypto.stableHmac(properties.getEncryptionKey(), subject).substring(0, 64);
    }

    private static OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static String trim(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.substring(0, Math.min(value.length(), maxLength));
    }
}
