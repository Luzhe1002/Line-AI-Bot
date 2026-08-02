package com.lineaibot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lineaibot.booking.BookingEventRepository;
import com.lineaibot.booking.BookingNotificationProcessor;
import com.lineaibot.line.LineEventProcessor;
import com.lineaibot.line.LineRepository;
import com.lineaibot.merchant.MerchantManageTokenService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
class MerchantBookingManagementIntegrationTest {

    private static final String PLATFORM_KEY = "test-platform-admin-key";
    private static final String LINE_SECRET = "test-line-channel-secret";
    private static final String LINE_ACCESS_TOKEN = "test-line-access-token";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private LineRepository lineRepository;

    @Autowired
    private LineEventProcessor lineEventProcessor;

    @Autowired
    private BookingEventRepository bookingEvents;

    @Autowired
    private BookingNotificationProcessor bookingNotifications;

    @Autowired
    private MerchantManageTokenService manageTokens;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void bookingIntentOnlyOpensTheNamedBookingPage() throws Exception {
        Tenant tenant = createTenant("named-booking");
        configureLineChannel(tenant);

        processLineText(tenant, "U-customer", "預約");

        String payload = latestOutbox(tenant.id(), "U-customer", "REPLY");
        assertThat(payload)
                .contains("開啟預約頁")
                .contains("/booking/" + tenant.slug())
                .contains("請點選下方「開啟預約頁」按鈕，選擇服務、時段並填寫預約姓名。")
                .doesNotContain("action=book")
                .doesNotContain("我要預約 ");
    }

    @Test
    void portalLinkBindsStaffAndLineAdminCanQueryNotifyAndCancel()
            throws Exception {
        Tenant tenant = createTenant("merchant-line");
        configureLineChannel(tenant);
        PortalSession portal = loginPortal(tenant);

        JsonNode link = json(mvc.perform(post("/portal/api/staff-links")
                        .session(portal.session())
                        .header("X-CSRF-Token", portal.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "display_name": "王店長",
                                  "role": "OWNER"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").isNotEmpty())
                .andReturn());

        processLineText(tenant, "U-owner", "綁定 " + link.path("code").asText());
        assertThat(latestOutbox(tenant.id(), "U-owner", "REPLY"))
                .contains("管理員綁定成功")
                .contains("管理預約");

        processLineText(tenant, "U-handoff-customer", "人工客服");
        assertThat(latestOutbox(tenant.id(), "U-owner", "PUSH"))
                .contains("顧客要求轉接人工客服")
                .contains("案件編號");
        processLineText(tenant, "U-handoff-customer", "人工客服");
        assertThat(jdbc.sql("""
                                select count(*) from outbox_messages
                                where tenant_id = :tenantId
                                  and line_user_id = 'U-owner'
                                  and delivery_type = 'PUSH'
                                  and dedupe_key like 'handoff:%'
                                """)
                        .param("tenantId", tenant.id())
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(jdbc.sql("""
                                select desired_role, desired_linked, status
                                from merchant_rich_menu_sync
                                where tenant_id = :tenantId
                                """)
                        .param("tenantId", tenant.id())
                        .query((rs, rowNum) -> List.of(
                                rs.getString("desired_role"),
                                Boolean.toString(rs.getBoolean("desired_linked")),
                                rs.getString("status")))
                        .single())
                .containsExactly("OWNER", "true", "READY");
        assertThat(jdbc.sql("""
                                select count(*)
                                from merchant_rich_menu_sync
                                where tenant_id = :tenantId
                                """)
                        .param("tenantId", tenant.id())
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);

        processLinePostback(tenant, "U-owner", "action=merchant_portal");
        String portalReply = latestOutbox(tenant.id(), "U-owner", "REPLY");
        assertThat(portalReply)
                .contains("開啟完整管理後台")
                .contains("/portal/#token=");
        String portalToken = tokenFromPortalReply(portalReply);
        MvcResult linePortalLogin = mvc.perform(post("/portal/api/line-session")
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andReturn();
        MockHttpSession linePortalSession =
                (MockHttpSession) linePortalLogin.getRequest().getSession(false);
        mvc.perform(get("/portal/api/overview").session(linePortalSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.id").value(tenant.id()));
        mvc.perform(post("/portal/api/line-session")
                        .header("Authorization", "Bearer " + portalToken))
                .andExpect(status().isUnauthorized());

        String staffId = jdbc.sql("""
                        select id from merchant_staff
                        where tenant_id = :tenantId and status = 'ACTIVE'
                        """)
                .param("tenantId", tenant.id())
                .query(String.class)
                .single();
        OpenSlot slot = firstOpenSlot(tenant);
        JsonNode reservation = createReservation(
                tenant, slot, "U-booking-customer", "陳小姐", "merchant-flow-0001");

        String createdEventId = eventId(
                tenant.id(), reservation.path("id").asText(), "RESERVATION_CREATED");
        assertThat(bookingEvents.claimEvent(createdEventId, Instant.now())).isTrue();
        bookingNotifications.process(createdEventId);
        assertThat(latestOutbox(tenant.id(), "U-owner", "PUSH"))
                .contains("收到新預約")
                .contains("陳小姐");
        assertThat(latestOutbox(tenant.id(), "U-booking-customer", "PUSH"))
                .contains("預約成功")
                .contains("陳小姐")
                .contains(reservation.path("id").asText().substring(0, 8).toUpperCase());

        processLineText(tenant, "U-owner", "本週預約");
        assertThat(latestOutbox(tenant.id(), "U-owner", "REPLY"))
                .contains("陳小姐")
                .contains("merchant_cancel_prompt");

        processLinePostback(
                tenant,
                "U-owner",
                "action=merchant_cancel_confirm&reservation_id="
                        + reservation.path("id").asText());
        assertThat(latestOutbox(tenant.id(), "U-owner", "REPLY"))
                .contains("預約已取消");
        assertThat(jdbc.sql("""
                                select status from reservations
                                where tenant_id = :tenantId and id = :reservationId
                                """)
                        .param("tenantId", tenant.id())
                        .param("reservationId", reservation.path("id").asText())
                        .query(String.class)
                        .single())
                .isEqualTo("CANCELLED");
        assertThat(jdbc.sql("""
                                select count(*) from booking_slot_occupancies
                                where tenant_id = :tenantId
                                  and reference_id = :reservationId
                                """)
                        .param("tenantId", tenant.id())
                        .param("reservationId", reservation.path("id").asText())
                        .query(Integer.class)
                        .single())
                .isZero();

        String cancelledEventId = eventId(
                tenant.id(), reservation.path("id").asText(), "RESERVATION_CANCELLED");
        assertThat(bookingEvents.claimEvent(cancelledEventId, Instant.now())).isTrue();
        bookingNotifications.process(cancelledEventId);
        assertThat(latestOutbox(tenant.id(), "U-booking-customer", "PUSH"))
                .contains("店家已取消");

        assertThat(staffId).isNotBlank();
        assertThat(jdbc.sql("""
                                select count(*) from booking_activity_logs
                                where tenant_id = :tenantId
                                  and reservation_id = :reservationId
                                """)
                        .param("tenantId", tenant.id())
                        .param("reservationId", reservation.path("id").asText())
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void oneTimeMerchantPageSessionCanBlockAndReleaseSlots()
            throws Exception {
        Tenant tenant = createTenant("merchant-page");
        configureLineChannel(tenant);
        PortalSession portal = loginPortal(tenant);
        String code = json(mvc.perform(post("/portal/api/staff-links")
                        .session(portal.session())
                        .header("X-CSRF-Token", portal.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"display_name":"排班主管","role":"MANAGER"}
                                """))
                .andExpect(status().isCreated())
                .andReturn())
                .path("code")
                .asText();
        processLineText(tenant, "U-manager-page", "綁定 " + code);
        String staffId = jdbc.sql("""
                        select id from merchant_staff
                        where tenant_id = :tenantId and status = 'ACTIVE'
                        """)
                .param("tenantId", tenant.id())
                .query(String.class)
                .single();

        String token = manageTokens.issue(tenant.id(), staffId);
        MvcResult login = mvc.perform(post(
                                "/merchant-booking/api/{tenantSlug}/session", tenant.slug())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.staff.role").value("MANAGER"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        String csrf = json(login).path("csrf_token").asText();

        mvc.perform(post("/merchant-booking/api/{tenantSlug}/session", tenant.slug())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        String portalOnlyToken = manageTokens.issuePortalLogin(tenant.id(), staffId);
        mvc.perform(post("/merchant-booking/api/{tenantSlug}/session", tenant.slug())
                        .header("Authorization", "Bearer " + portalOnlyToken))
                .andExpect(status().isUnauthorized());
        String bookingOnlyToken = manageTokens.issue(tenant.id(), staffId);
        mvc.perform(post("/portal/api/line-session")
                        .header("Authorization", "Bearer " + bookingOnlyToken))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/merchant-booking/{tenantSlug}", tenant.slug()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .forwardedUrl("/merchant-booking/index.html"));

        OpenSlot slot = firstOpenSlot(tenant);
        JsonNode block = json(mvc.perform(post(
                                "/merchant-booking/api/{tenantSlug}/blocks", tenant.slug())
                        .session(session)
                        .header("X-CSRF-Token", csrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "starts_at": "%s",
                                  "reason": "內部會議"
                                }
                                """
                                .formatted(slot.startsAt())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andReturn());

        mvc.perform(post("/api/v1/tenants/{tenantId}/reservations", tenant.id())
                        .header("X-Tenant-Api-Key", tenant.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "service_id": "%s",
                                  "line_user_id": "U-blocked-customer",
                                  "starts_at": "%s",
                                  "customer_name": "被封鎖顧客",
                                  "idempotency_key": "blocked-slot-0001"
                                }
                                """
                                .formatted(slot.serviceId(), slot.startsAt())))
                .andExpect(status().isConflict());

        mvc.perform(delete(
                                "/merchant-booking/api/{tenantSlug}/blocks/{blockId}",
                                tenant.slug(),
                                block.path("id").asText())
                        .session(session)
                        .header("X-CSRF-Token", csrf))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        createReservation(
                tenant, slot, "U-after-release", "解除後顧客", "released-slot-0001");
        assertThat(jdbc.sql("""
                                select count(*) from booking_activity_logs
                                where tenant_id = :tenantId
                                  and action in ('SLOT_BLOCKED', 'SLOT_UNBLOCKED')
                                """)
                        .param("tenantId", tenant.id())
                        .query(Integer.class)
                        .single())
                .isEqualTo(2);

        mvc.perform(put("/portal/api/staff/{staffId}", staffId)
                        .session(portal.session())
                        .header("X-CSRF-Token", portal.csrfToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DISABLED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        assertThat(jdbc.sql("""
                                select desired_linked, status
                                from merchant_rich_menu_sync
                                where tenant_id = :tenantId
                                  and staff_id = :staffId
                                """)
                        .param("tenantId", tenant.id())
                        .param("staffId", staffId)
                        .query((rs, rowNum) -> List.of(
                                Boolean.toString(rs.getBoolean("desired_linked")),
                                rs.getString("status")))
                        .single())
                .containsExactly("false", "READY");
    }

    private PortalSession loginPortal(Tenant tenant) throws Exception {
        MvcResult login = mvc.perform(post("/portal/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenant_id":"%s","api_key":"%s"}
                                """
                                .formatted(tenant.id(), tenant.apiKey())))
                .andExpect(status().isOk())
                .andReturn();
        return new PortalSession(
                (MockHttpSession) login.getRequest().getSession(false),
                json(login).path("csrf_token").asText());
    }

    private void processLineText(Tenant tenant, String lineUserId, String text) {
        processLineEvent(
                tenant,
                lineUserId,
                "message",
                """
                {
                  "type": "message",
                  "replyToken": "%s",
                  "source": {"type": "user", "userId": "%s"},
                  "message": {"id": "%s", "type": "text", "text": "%s"}
                }
                """
                        .formatted(
                                "reply-" + UUID.randomUUID(),
                                lineUserId,
                                UUID.randomUUID(),
                                text));
    }

    private void processLinePostback(Tenant tenant, String lineUserId, String data) {
        processLineEvent(
                tenant,
                lineUserId,
                "postback",
                """
                {
                  "type": "postback",
                  "replyToken": "%s",
                  "source": {"type": "user", "userId": "%s"},
                  "postback": {"data": "%s"}
                }
                """
                        .formatted("reply-" + UUID.randomUUID(), lineUserId, data));
    }

    private void processLineEvent(
            Tenant tenant, String lineUserId, String eventType, String payload) {
        String id = UUID.randomUUID().toString();
        lineRepository.insertEvent(
                id,
                tenant.id(),
                "webhook-" + UUID.randomUUID(),
                eventType,
                lineUserId,
                payload,
                Instant.now());
        assertThat(lineRepository.claimEvent(id, Instant.now())).isTrue();
        lineEventProcessor.process(id);
        assertThat(lineRepository.findEvent(id).orElseThrow().status())
                .isEqualTo("PROCESSED");
    }

    private JsonNode createReservation(
            Tenant tenant,
            OpenSlot slot,
            String lineUserId,
            String customerName,
            String idempotencyKey)
            throws Exception {
        return json(mvc.perform(post("/api/v1/tenants/{tenantId}/reservations", tenant.id())
                        .header("X-Tenant-Api-Key", tenant.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "service_id": "%s",
                                  "line_user_id": "%s",
                                  "starts_at": "%s",
                                  "customer_name": "%s",
                                  "idempotency_key": "%s"
                                }
                                """
                                .formatted(
                                        slot.serviceId(),
                                        lineUserId,
                                        slot.startsAt(),
                                        customerName,
                                        idempotencyKey)))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private OpenSlot firstOpenSlot(Tenant tenant) throws Exception {
        JsonNode services = json(mvc.perform(get(
                                "/api/v1/tenants/{tenantId}/booking-services", tenant.id())
                        .header("X-Tenant-Api-Key", tenant.apiKey()))
                .andExpect(status().isOk())
                .andReturn());
        String serviceId = services.get(0).path("id").asText();
        LocalDate start = LocalDate.now(ZoneId.of("Asia/Taipei"));
        for (int offset = 0; offset < 14; offset++) {
            JsonNode availability = json(mvc.perform(get(
                                    "/api/v1/tenants/{tenantId}/availability", tenant.id())
                            .header("X-Tenant-Api-Key", tenant.apiKey())
                            .queryParam("service_id", serviceId)
                            .queryParam("local_date", start.plusDays(offset).toString()))
                    .andExpect(status().isOk())
                    .andReturn());
            if (!availability.path("slots").isEmpty()) {
                return new OpenSlot(
                        serviceId,
                        availability.path("slots").get(0).path("starts_at").asText());
            }
        }
        throw new AssertionError("No open slot found");
    }

    private String eventId(String tenantId, String reservationId, String eventType) {
        return jdbc.sql("""
                        select id from booking_events
                        where tenant_id = :tenantId
                          and reservation_id = :reservationId
                          and event_type = :eventType
                        """)
                .param("tenantId", tenantId)
                .param("reservationId", reservationId)
                .param("eventType", eventType)
                .query(String.class)
                .single();
    }

    private String latestOutbox(
            String tenantId, String lineUserId, String deliveryType) {
        return jdbc.sql("""
                        select payload_json from outbox_messages
                        where tenant_id = :tenantId
                          and line_user_id = :lineUserId
                          and delivery_type = :deliveryType
                        order by created_at desc
                        limit 1
                        """)
                .param("tenantId", tenantId)
                .param("lineUserId", lineUserId)
                .param("deliveryType", deliveryType)
                .query(String.class)
                .single();
    }

    private String tokenFromPortalReply(String payload) {
        Matcher matcher = Pattern.compile("/portal/#token=([^\"&]+)").matcher(payload);
        assertThat(matcher.find()).isTrue();
        return java.net.URLDecoder.decode(
                matcher.group(1), java.nio.charset.StandardCharsets.UTF_8);
    }

    private void configureLineChannel(Tenant tenant) throws Exception {
        mvc.perform(put("/api/v1/tenants/{tenantId}/line-channel", tenant.id())
                        .header("X-Tenant-Api-Key", tenant.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "channel_secret": "%s",
                                  "channel_access_token": "%s",
                                  "enabled": true
                                }
                                """
                                .formatted(LINE_SECRET, LINE_ACCESS_TOKEN)))
                .andExpect(status().isOk());
    }

    private Tenant createTenant(String purpose) throws Exception {
        String slug = purpose + "-" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode value = json(mvc.perform(post("/api/v1/tenants")
                        .header("X-Platform-Admin-Key", PLATFORM_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Merchant management test",
                                  "slug": "%s",
                                  "timezone": "Asia/Taipei",
                                  "slot_minutes": 60
                                }
                                """
                                .formatted(slug)))
                .andExpect(status().isCreated())
                .andReturn());
        return new Tenant(
                value.path("id").asText(),
                value.path("slug").asText(),
                value.path("admin_api_key").asText());
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private record Tenant(String id, String slug, String apiKey) {}

    private record PortalSession(MockHttpSession session, String csrfToken) {}

    private record OpenSlot(String serviceId, String startsAt) {}
}
