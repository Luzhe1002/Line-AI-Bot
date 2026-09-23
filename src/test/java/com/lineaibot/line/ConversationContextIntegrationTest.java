package com.lineaibot.line;

import static org.assertj.core.api.Assertions.assertThat;

import com.lineaibot.config.AppProperties;
import com.lineaibot.knowledge.KnowledgeDtos;
import com.lineaibot.knowledge.KnowledgeService;
import com.lineaibot.tenant.TenantDtos;
import com.lineaibot.tenant.TenantRepository;
import com.lineaibot.tenant.TenantService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
class ConversationContextIntegrationTest {
    @Autowired TenantService tenants;
    @Autowired TenantRepository tenantRepository;
    @Autowired LineRepository events;
    @Autowired ConversationContextRepository turns;
    @Autowired ConversationContextService contexts;
    @Autowired LineEventProcessor processor;
    @Autowired KnowledgeService knowledge;
    @Autowired AppProperties properties;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcClient jdbc;

    private TenantRepository.TenantRow tenant() {
        var created = tenants.createTenant(new TenantDtos.TenantCreate("Context test", "ctx-" + UUID.randomUUID(), "Asia/Taipei", 60, true));
        var row = tenantRepository.findById(created.id()).orElseThrow();
        tenants.configureLineChannel(row, new TenantDtos.LineChannelUpsert("context-test-secret", "context-test-access", true));
        return row;
    }

    private String event(String tenant, String user, String source, String text, Instant now) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> sourceNode = source.startsWith("group:")
                ? Map.of("type", "group", "groupId", source.substring(6), "userId", user)
                : Map.of("type", "user", "userId", user);
        events.insertEvent(id, tenant, UUID.randomUUID().toString(), "message", user,
                mapper.writeValueAsString(Map.of("type", "message", "replyToken", UUID.randomUUID().toString(),
                        "source", sourceNode, "message", Map.of("type", "text", "text", text))), now);
        return id;
    }

    private void saved(String tenant, String user, String source, String text, Instant time, boolean delivered) {
        String id = event(tenant, user, source, text, time);
        var reply = new ConversationContext.Reply(List.of(Map.of("type", "text", "text", "回答")),
                new ConversationContext.State(text, text + "多少錢", "", "", ""));
        turns.prepare(tenant, user, source, id, text, "text", reply, time);
        if (delivered) turns.delivered(tenant, user, id, time);
        events.markEventProcessed(id, time);
    }

    @Test
    void contextIsIsolatedBoundedAndOnlyIncludesDeliveredTurns() {
        var a = tenant(); var b = tenant();
        Instant now = Instant.now();
        saved(a.id(), "U1", "user:U1", "過期", now.minusSeconds(1801), true);
        saved(a.id(), "U1", "user:U1", "未送出", now.minusSeconds(30), false);
        saved(a.id(), "U2", "user:U2", "別人", now.minusSeconds(29), true);
        saved(b.id(), "U1", "user:U1", "別家", now.minusSeconds(28), true);
        saved(a.id(), "U1", "group:G", "群組", now.minusSeconds(27), true);
        for (int i = 0; i < 7; i++) saved(a.id(), "U1", "user:U1", "服務" + i, now.minusSeconds(20 - i), true);
        var history = contexts.load(a.id(), "U1", "user:U1", now);
        assertThat(history.turns()).hasSize(5);
        assertThat(history.turns()).extracting(ConversationContext.Turn::customer)
                .containsExactly("服務2", "服務3", "服務4", "服務5", "服務6");
        assertThat(contexts.load(a.id(), "U1", "user:U1", now.plusSeconds(1801)).turns()).isEmpty();
        int original = properties.getConversation().getMaxChars();
        try {
            properties.getConversation().setMaxChars(1000);
            saved(a.id(), "U1", "user:U1", "大".repeat(1100), now, true);
            assertThat(contexts.load(a.id(), "U1", "user:U1", now.plusMillis(1)).turns()).isEmpty();
        } finally { properties.getConversation().setMaxChars(original); }
    }

    @Test
    void sameUserIsOrderedAcrossRetriesWhileOtherUsersCanRun() {
        var tenant = tenant();
        Instant now = Instant.parse("2026-09-18T01:00:00.123456789Z");
        String first = event(tenant.id(), "ordered", "user:ordered", "第一句", now);
        String second = event(tenant.id(), "ordered", "user:ordered", "第二句", now);
        String other = event(tenant.id(), "other", "user:other", "其他人", now);
        assertThat(events.findReadyEventIds(now, 100)).contains(first, other).doesNotContain(second);
        assertThat(events.claimEvent(second, now)).isFalse();
        assertThat(events.claimEvent(first, now)).isTrue();
        assertThat(events.claimEvent(other, now)).isTrue();
        events.releaseClaim(first, now.plusSeconds(5), "retry");
        assertThat(events.claimEvent(second, now.plusSeconds(1))).isFalse();
        events.markEventProcessed(first, now.plusSeconds(6));
        assertThat(events.claimEvent(second, now.plusSeconds(6))).isTrue();
        events.markEventProcessed(second, now.plusSeconds(6));
        events.markEventProcessed(other, now.plusSeconds(6));
    }

    @Test
    void endToEndFollowUpUsesTopicAndResetClearsIt() {
        var tenant = tenant();
        var dataset = knowledge.createDataset(tenant, new KnowledgeDtos.DatasetCreate("context", 2));
        knowledge.addDocument(tenant, dataset.id(), new KnowledgeDtos.KnowledgeDocumentCreate("深層清潔", "深層清潔價格為800元。深層清潔要多久：需要60分鐘。取消預約手續費為0元。", null));
        knowledge.publishDataset(tenant, dataset.id());
        process(tenant.id(), "U-flow", "深層清潔多少錢？");
        String next = process(tenant.id(), "U-flow", "那要多久？");
        var reply = turns.prepared(tenant.id(), next).orElseThrow();
        assertThat(reply.state().question()).contains("深層清潔", "多久");
        assertThat(reply.messages().getFirst().get("text").toString()).contains("60");
        String policy = process(tenant.id(), "U-flow", "取消要收費嗎？");
        assertThat(turns.prepared(tenant.id(), policy).orElseThrow().messages().getFirst().get("text").toString())
                .doesNotContain("請選擇要取消", "查不到可取消");
        process(tenant.id(), "U-flow", "重新開始");
        String afterReset = process(tenant.id(), "U-flow", "那要多久？");
        assertThat(turns.prepared(tenant.id(), afterReset).orElseThrow().state().pendingQuestion()).isNotBlank();
    }

    @Test
    void retryReusesPreparedReplyAndRecordsDeliveredHistoryOnce() {
        var tenant = tenant();
        String id = event(tenant.id(), "U-retry", "user:U-retry", "不要重新生成", Instant.now());
        var reply = new ConversationContext.Reply(List.of(Map.of("type", "text", "text", "既有回覆")),
                new ConversationContext.State("服務", "服務價格", "", "", ""));
        turns.prepare(tenant.id(), "U-retry", "user:U-retry", id, "不要重新生成", "text", reply, Instant.now());
        assertThat(contexts.load(tenant.id(), "U-retry", "user:U-retry", Instant.now()).turns()).isEmpty();
        assertThat(events.claimEvent(id, Instant.now())).isTrue();
        processor.process(id);
        events.releaseClaim(id, Instant.now(), "ignored after processed");
        processor.process(id);
        turns.delivered(tenant.id(), "U-retry", id, Instant.now());
        assertThat(contexts.load(tenant.id(), "U-retry", "user:U-retry", Instant.now()).turns()).hasSize(1);
        assertThat(jdbc.sql("select count(*) from conversation_messages where tenant_id = :tenant")
                .param("tenant", tenant.id()).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void operationalRepliesAreNotExposedToUnderstanding() {
        var tenant = tenant();
        Instant now = Instant.now();
        saved(tenant.id(), "U-private", "user:U-private", "舊主題", now.minusSeconds(1), true);
        String id = event(tenant.id(), "U-private", "user:U-private", "管理後台", now);
        turns.prepare(tenant.id(), "U-private", "user:U-private", id, "管理後台", "text",
                ConversationContext.Reply.plain(List.of(Map.of("type", "text", "text", "https://example.test/#token=secret"))), now);
        turns.delivered(tenant.id(), "U-private", id, now);
        assertThat(contexts.load(tenant.id(), "U-private", "user:U-private", now.plusSeconds(1)).turns()).isEmpty();
        events.markEventProcessed(id, now);
    }

    @Test
    void unansweredLatestTopicDoesNotReuseOlderTopic() {
        var tenant = tenant();
        Instant now = Instant.now();
        saved(tenant.id(), "U-failed", "user:U-failed", "清潔", now.minusSeconds(2), true);
        saved(tenant.id(), "U-failed", "user:U-failed", "按摩", now.minusSeconds(1), false);
        assertThat(contexts.load(tenant.id(), "U-failed", "user:U-failed", now).turns()).isEmpty();
    }

    @Test
    void disablingKnowledgeMemoryKeepsScopedCancellationConfirmation() {
        var tenant = tenant();
        Instant now = Instant.now();
        String id = event(tenant.id(), "U-confirm", "user:U-confirm", "cancel", now);
        turns.prepare(tenant.id(), "U-confirm", "user:U-confirm", id, "cancel", "postback",
                new ConversationContext.Reply(List.of(Map.of("type", "text", "text", "確認取消？")),
                        new ConversationContext.State("", "", "", "reservation", "confirmation")), now);
        turns.delivered(tenant.id(), "U-confirm", id, now);
        properties.getConversation().setEnabled(false);
        try {
            assertThat(contexts.load(tenant.id(), "U-confirm", "user:U-confirm", now).latestState().confirmationToken()).isEqualTo("confirmation");
            assertThat(contexts.load(tenant.id(), "U-confirm", "group:G", now).latestState().confirmationToken()).isEmpty();
            assertThat(contexts.load(tenant.id(), "U-other", "user:U-confirm", now).latestState().confirmationToken()).isEmpty();
            assertThat(contexts.load(tenant.id(), "U-confirm", "user:U-confirm", now.plusSeconds(1801)).latestState().confirmationToken()).isEmpty();
        } finally { properties.getConversation().setEnabled(true); }
        events.markEventProcessed(id, now);
    }

    private String process(String tenant, String user, String text) {
        String id = event(tenant, user, "user:" + user, text, Instant.now().minusMillis(1));
        assertThat(events.claimEvent(id, Instant.now())).isTrue();
        processor.process(id);
        assertThat(events.findEvent(id).orElseThrow().status()).isEqualTo("PROCESSED");
        return id;
    }

    @Test
    void handoffStoresRecentQuestionsAndStartsANewContextBoundary() {
        var tenant = tenant();
        process(tenant.id(), "U-handoff", "深層清潔多少錢？");
        process(tenant.id(), "U-handoff", "我要人工客服");
        String reason = jdbc.sql("select reason from handoff_tickets where tenant_id = :tenant and line_user_id = :user")
                .param("tenant", tenant.id()).param("user", "U-handoff").query(String.class).single();
        assertThat(reason).contains("深層清潔", "最近詢問");
        assertThat(contexts.load(tenant.id(), "U-handoff", "user:U-handoff", Instant.now()).turns()).isEmpty();
    }
}
