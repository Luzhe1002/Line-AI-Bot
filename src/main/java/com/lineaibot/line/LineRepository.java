package com.lineaibot.line;

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
public class LineRepository {

    public record LineEventRow(
            String id,
            String tenantId,
            String webhookEventId,
            String eventType,
            String lineUserId,
            String payloadJson,
            String status,
            int attempts) {}

    private final JdbcClient jdbc;

    public LineRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertEvent(
            String id,
            String tenantId,
            String webhookEventId,
            String eventType,
            String lineUserId,
            String payloadJson,
            Instant receivedAt) {
        jdbc.sql("""
                        insert into line_events (
                            id, tenant_id, webhook_event_id, event_type, line_user_id,
                            payload_json, status, attempts, next_attempt_at,
                            locked_at, error, received_at, processed_at
                        ) values (
                            :id, :tenantId, :webhookEventId, :eventType, :lineUserId,
                            :payloadJson, 'PENDING', 0, :receivedAt,
                            null, null, :receivedAt, null
                        )
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("webhookEventId", webhookEventId)
                .param("eventType", eventType)
                .param("lineUserId", lineUserId)
                .param("payloadJson", payloadJson)
                .param("receivedAt", utc(receivedAt))
                .update();
    }

    public List<String> findReadyEventIds(Instant now, int limit) {
        return jdbc.sql("""
                        select id from line_events
                        where status in ('PENDING', 'RETRY')
                          and next_attempt_at <= :now
                        order by received_at
                        limit :limit
                        """)
                .param("now", utc(now))
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    public boolean claimEvent(String eventId, Instant now) {
        return jdbc.sql("""
                        update line_events
                        set status = 'PROCESSING', attempts = attempts + 1,
                            locked_at = :now, error = null
                        where id = :id and status in ('PENDING', 'RETRY')
                          and next_attempt_at <= :now
                        """)
                        .param("now", utc(now))
                        .param("id", eventId)
                        .update()
                == 1;
    }

    public void recoverStaleEvents(Instant staleBefore, Instant retryAt) {
        jdbc.sql("""
                        update line_events
                        set status = case when attempts >= 3 then 'FAILED' else 'RETRY' end,
                            next_attempt_at = :retryAt, locked_at = null,
                            processed_at = case when attempts >= 3 then :retryAt
                                                else null end,
                            error = 'Recovered stale worker claim'
                        where status = 'PROCESSING' and locked_at < :staleBefore
                        """)
                .param("retryAt", utc(retryAt))
                .param("staleBefore", utc(staleBefore))
                .update();
    }

    public void releaseClaim(String eventId, Instant retryAt, String error) {
        jdbc.sql("""
                        update line_events
                        set status = 'RETRY', next_attempt_at = :retryAt, locked_at = null,
                            error = :error
                        where id = :id and status = 'PROCESSING'
                        """)
                .param("retryAt", utc(retryAt))
                .param("error", truncate(error, 4000))
                .param("id", eventId)
                .update();
    }

    public Optional<LineEventRow> findEvent(String eventId) {
        return jdbc.sql("select * from line_events where id = :id")
                .param("id", eventId)
                .query(this::mapEvent)
                .optional();
    }

    public void markEventProcessed(String eventId, Instant processedAt) {
        jdbc.sql("""
                        update line_events
                        set status = 'PROCESSED', processed_at = :processedAt,
                            locked_at = null, error = null
                        where id = :id
                        """)
                .param("processedAt", utc(processedAt))
                .param("id", eventId)
                .update();
    }

    public void markEventRetryOrFailed(
            String eventId, int attempts, String error, Instant nextAttemptAt) {
        String status = attempts < 3 ? "RETRY" : "FAILED";
        jdbc.sql("""
                        update line_events
                        set status = :status, error = :error, locked_at = null,
                            next_attempt_at = :nextAttemptAt,
                            processed_at = case when :status = 'FAILED' then :nextAttemptAt
                                                else null end
                        where id = :id
                        """)
                .param("status", status)
                .param("error", truncate(error, 4000))
                .param("nextAttemptAt", utc(nextAttemptAt))
                .param("id", eventId)
                .update();
    }

    public void recordConversationMessage(
            String tenantId,
            String lineUserId,
            String direction,
            String messageType,
            String content,
            String metadataJson,
            Instant createdAt) {
        jdbc.sql("""
                        insert into conversation_messages (
                            id, tenant_id, line_user_id, direction, message_type,
                            content, metadata_json, created_at
                        ) values (
                            :id, :tenantId, :lineUserId, :direction, :messageType,
                            :content, :metadataJson, :createdAt
                        )
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("tenantId", tenantId)
                .param("lineUserId", lineUserId)
                .param("direction", direction)
                .param("messageType", messageType)
                .param("content", content)
                .param("metadataJson", metadataJson)
                .param("createdAt", utc(createdAt))
                .update();
    }

    public boolean hasOpenHandoff(String tenantId, String lineUserId) {
        return jdbc.sql("""
                        select count(*) from handoff_tickets
                        where tenant_id = :tenantId and line_user_id = :lineUserId
                          and status = 'OPEN'
                        """)
                        .param("tenantId", tenantId)
                        .param("lineUserId", lineUserId)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    public void insertHandoff(
            String tenantId, String lineUserId, String reason, Instant createdAt) {
        jdbc.sql("""
                        insert into handoff_tickets (
                            id, tenant_id, line_user_id, status, reason, created_at, closed_at
                        ) values (
                            :id, :tenantId, :lineUserId, 'OPEN', :reason, :createdAt, null
                        )
                        """)
                .param("id", UUID.randomUUID().toString())
                .param("tenantId", tenantId)
                .param("lineUserId", lineUserId)
                .param("reason", reason)
                .param("createdAt", utc(createdAt))
                .update();
    }

    public String insertOutbox(
            String tenantId,
            String lineUserId,
            String replyToken,
            String deliveryType,
            String payloadJson,
            Instant createdAt) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("""
                        insert into outbox_messages (
                            id, tenant_id, line_user_id, reply_token, delivery_type,
                            payload_json, status, attempts, error, created_at, sent_at
                        ) values (
                            :id, :tenantId, :lineUserId, :replyToken, :deliveryType,
                            :payloadJson, 'PENDING', 0, null, :createdAt, null
                        )
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("lineUserId", lineUserId)
                .param("replyToken", replyToken)
                .param("deliveryType", deliveryType)
                .param("payloadJson", payloadJson)
                .param("createdAt", utc(createdAt))
                .update();
        return id;
    }

    public Optional<String> findLatestFailedReplyPayload(
            String tenantId, String lineUserId, String replyToken) {
        return jdbc.sql("""
                        select payload_json from outbox_messages
                        where tenant_id = :tenantId
                          and line_user_id = :lineUserId
                          and reply_token = :replyToken
                          and delivery_type = 'REPLY'
                          and status = 'FAILED'
                        order by created_at desc
                        limit 1
                        """)
                .param("tenantId", tenantId)
                .param("lineUserId", lineUserId)
                .param("replyToken", replyToken)
                .query(String.class)
                .optional();
    }

    public void markOutboxSent(String id, String status, Instant sentAt) {
        jdbc.sql("""
                        update outbox_messages
                        set status = :status, attempts = attempts + 1,
                            sent_at = :sentAt, error = null
                        where id = :id
                        """)
                .param("status", status)
                .param("sentAt", utc(sentAt))
                .param("id", id)
                .update();
    }

    public void markOutboxFailed(String id, String error) {
        jdbc.sql("""
                        update outbox_messages
                        set status = 'FAILED', attempts = attempts + 1, error = :error
                        where id = :id
                        """)
                .param("error", truncate(error, 2000))
                .param("id", id)
                .update();
    }

    private LineEventRow mapEvent(ResultSet rs, int rowNum) throws SQLException {
        return new LineEventRow(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("webhook_event_id"),
                rs.getString("event_type"),
                rs.getString("line_user_id"),
                rs.getString("payload_json"),
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
