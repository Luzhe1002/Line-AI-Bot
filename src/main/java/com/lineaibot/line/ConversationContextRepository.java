package com.lineaibot.line;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
public class ConversationContextRepository {
    public record StoredTurn(String customer, String replyJson, String stateJson, Instant createdAt, boolean delivered) {}

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final LineRepository messages;

    public ConversationContextRepository(JdbcClient jdbc, ObjectMapper mapper, LineRepository messages) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.messages = messages;
    }

    public Optional<ConversationContext.Reply> prepared(String tenantId, String eventId) {
        return jdbc.sql("select reply_json, state_json from conversation_turns where tenant_id = :tenant and event_id = :event")
                .param("tenant", tenantId).param("event", eventId)
                .query((rs, n) -> new ConversationContext.Reply(
                        mapper.readValue(rs.getString("reply_json"),
                                new tools.jackson.core.type.TypeReference<java.util.List<java.util.Map<String, Object>>>() {}),
                        mapper.readValue(rs.getString("state_json"), ConversationContext.State.class)))
                .optional();
    }

    public List<StoredTurn> recent(String tenantId, String userId, String sourceKey,
                                  Instant after, Instant before, int limit) {
        return jdbc.sql("""
                select t.customer_text, t.reply_json, t.state_json, t.created_at, t.delivered_at
                from conversation_turns t join line_events e on e.id = t.event_id
                where t.tenant_id = :tenant and t.line_user_id = :user and t.source_key = :source
                  and t.created_at > :after and t.created_at <= :before
                order by e.event_sequence desc limit :limit
                """)
                .param("tenant", tenantId).param("user", userId).param("source", sourceKey)
                .param("after", utc(after)).param("before", utc(before))
                .param("limit", limit)
                .query((rs, n) -> new StoredTurn(rs.getString("customer_text"), rs.getString("reply_json"),
                        rs.getString("state_json"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        rs.getObject("delivered_at") != null))
                .list();
    }

    @Transactional
    public void prepare(String tenantId, String userId, String sourceKey, String eventId,
                        String text, String messageType, ConversationContext.Reply reply, Instant createdAt) {
        jdbc.sql("""
                insert into conversation_turns
                    (event_id, tenant_id, line_user_id, source_key, customer_text, reply_json, state_json, created_at)
                values (:event, :tenant, :user, :source, :text, :reply, :state, :created)
                """)
                .param("event", eventId).param("tenant", tenantId).param("user", userId)
                .param("source", sourceKey).param("text", text)
                .param("reply", mapper.writeValueAsString(reply.messages()))
                .param("state", mapper.writeValueAsString(reply.state()))
                .param("created", utc(createdAt)).update();
        messages.recordConversationMessage(tenantId, userId, "INBOUND", messageType, text,
                mapper.writeValueAsString(java.util.Map.of("event_id", eventId, "source_key", sourceKey)), createdAt);
    }

    @Transactional
    public void delivered(String tenantId, String userId, String eventId, Instant now) {
        int changed = jdbc.sql("""
                update conversation_turns set delivered_at = :now
                where tenant_id = :tenant and line_user_id = :user and event_id = :event and delivered_at is null
                """).param("now", utc(now)).param("tenant", tenantId)
                .param("user", userId).param("event", eventId).update();
        if (changed == 1) {
            for (var message : prepared(tenantId, eventId).orElseThrow().messages()) {
                messages.recordConversationMessage(tenantId, userId, "OUTBOUND",
                        message.getOrDefault("type", "text").toString(),
                        message.getOrDefault("text", "").toString(), mapper.writeValueAsString(message), now);
            }
        }
    }

    private OffsetDateTime utc(Instant value) {
        return value.truncatedTo(java.time.temporal.ChronoUnit.MICROS).atOffset(ZoneOffset.UTC);
    }
}
