package com.lineaibot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lineaibot.booking.BookingAccessTokenService;
import com.lineaibot.line.LineEventProcessor;
import com.lineaibot.line.LineRepository;
import com.lineaibot.shared.SecurityHeadersFilter;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
class ApplicationIntegrationTest {

    private static final String PLATFORM_KEY = "test-platform-admin-key";
    private static final String LINE_SECRET = "test-line-channel-secret";
    private static final String LINE_ACCESS_TOKEN = "test-line-access-token";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LineRepository lineRepository;

    @Autowired
    private LineEventProcessor lineEventProcessor;

    @Autowired
    private BookingAccessTokenService bookingAccessTokens;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private SecurityHeadersFilter securityHeadersFilter;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(securityHeadersFilter)
                .build();
    }

    @Test
    void platformAndTenantAuthenticationProtectTheApi() throws Exception {
        mvc.perform(post("/api/v1/tenants")
                        .header("X-Platform-Admin-Key", "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Wrong key",
                                  "slug": "wrong-key"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid platform admin API key"));

        Tenant tenant = createTenant("auth");

        mvc.perform(get("/api/v1/tenants/{tenantId}", tenant.id())
                        .header("X-Tenant-Api-Key", "wrong"))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/v1/tenants/{tenantId}", tenant.id())
                        .header("X-Tenant-Api-Key", tenant.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tenant.id()))
                .andExpect(jsonPath("$.slug").value(tenant.slug()));
    }

    @Test
    void healthCheckVerifiesApplicationAndDatabaseReadiness() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.service").value("LINE AI Bot"));
    }

    @Test
    void browserSecurityHeadersAndSensitiveApiCachePolicyAreApplied() throws Exception {
        mvc.perform(get("/portal/"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string(
                        "Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("frame-ancestors 'none'")));

        mvc.perform(get("/portal/api/session"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"));
    }

    @Test
    void merchantPortalUsesSessionAndCsrfWithoutPersistingTheApiKey() throws Exception {
        mvc.perform(get("/portal/"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .forwardedUrl("/portal/index.html"));
        mvc.perform(get("/portal/index.html"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content()
                        .string(org.hamcrest.Matchers.containsString(
                                "LINE AI MERCHANT PORTAL")));

        Tenant tenant = createTenant("portal");
        MvcResult login = mvc.perform(post("/portal/api/session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "tenant_id": "%s",
                                  "api_key": "%s"
                                }
                                """
                                .formatted(tenant.id(), tenant.apiKey())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.tenant.id").value(tenant.id()))
                .andExpect(jsonPath("$.csrf_token").isNotEmpty())
                .andReturn();

        JsonNode sessionView = json(login);
        String csrfToken = sessionView.path("csrf_token").asText();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).isNotNull();
        assertThat(java.util.Collections.list(session.getAttributeNames()))
                .doesNotContain("apiKey", "tenantApiKey");

        JsonNode overview = json(mvc.perform(get("/portal/api/overview").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant.id").value(tenant.id()))
                .andReturn());
        String datasetId = overview.path("datasets").get(0).path("id").asText();

        mvc.perform(post("/portal/api/documents")
                        .session(session)
                        .queryParam("datasetId", datasetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Portal policy",
                                  "content": "Portal uploads require review before publication. 本店接受現金及信用卡付款。"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Invalid CSRF token"));

        mvc.perform(post("/portal/api/documents")
                        .session(session)
                        .header("X-CSRF-Token", csrfToken)
                        .queryParam("datasetId", datasetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Portal policy",
                                  "content": "Portal uploads require review before publication. 本店接受現金及信用卡付款。"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.index_status").value("READY"));

        mvc.perform(post("/portal/api/answer")
                        .session(session)
                        .header("X-CSRF-Token", csrfToken)
                        .queryParam("datasetId", datasetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "What requires review before publication?"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.dataset_id").value(datasetId))
                .andExpect(jsonPath("$.citations[0].title").value("Portal policy"))
                .andExpect(jsonPath("$.citations[0].snippet")
                        .value(org.hamcrest.Matchers.containsString("信用卡")));

        mvc.perform(post("/portal/api/answer")
                        .session(session)
                        .header("X-CSRF-Token", csrfToken)
                        .queryParam("datasetId", datasetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "可以刷卡嗎？"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.dataset_id").value(datasetId))
                .andExpect(jsonPath("$.answer")
                        .value("本店接受現金及信用卡付款。"))
                .andExpect(jsonPath("$.citations[0].title").value("Portal policy"))
                .andExpect(jsonPath("$.citations[0].snippet")
                        .value(org.hamcrest.Matchers.containsString("信用卡")));

        Tenant otherTenant = createTenant("portal-preview-other");
        String otherDatasetId = firstId(getJson(
                "/api/v1/tenants/" + otherTenant.id() + "/datasets",
                otherTenant.apiKey()));
        mvc.perform(post("/portal/api/answer")
                        .session(session)
                        .header("X-CSRF-Token", csrfToken)
                        .queryParam("datasetId", otherDatasetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "Can this tenant read another tenant's draft?"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Dataset not found"));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "merchant-faq.md",
                "text/markdown",
                "## Opening hours\nWe open at nine.".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/portal/api/documents/upload")
                        .file(file)
                        .session(session)
                        .header("X-CSRF-Token", csrfToken)
                        .queryParam("datasetId", datasetId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("merchant-faq.md"))
                .andExpect(jsonPath("$.index_status").value("READY"));

        mvc.perform(delete("/portal/api/session").session(session))
                .andExpect(status().isOk());
        mvc.perform(get("/portal/api/overview").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publishedKnowledgeCanBeCopiedEditedAndDeletedInANewDraft()
            throws Exception {
        Tenant tenant = createTenant("knowledge-edit");
        String datasetId = firstId(getJson(
                "/api/v1/tenants/" + tenant.id() + "/datasets",
                tenant.apiKey()));

        MvcResult created = mvc.perform(post(
                                "/api/v1/tenants/{tenantId}/datasets/{datasetId}/documents",
                                tenant.id(),
                                datasetId)
                        .header("X-Tenant-Api-Key", tenant.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "付款方式",
                                  "content": "本店接受現金及信用卡付款。"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String activeDocumentId = json(created).path("id").asText();

        mvc.perform(post(
                                "/api/v1/tenants/{tenantId}/datasets/{datasetId}/publish",
                                tenant.id(),
                                datasetId)
                        .header("X-Tenant-Api-Key", tenant.apiKey()))
                .andExpect(status().isOk());

        mvc.perform(put(
                                "/api/v1/tenants/{tenantId}/datasets/{datasetId}/documents/{documentId}",
                                tenant.id(),
                                datasetId,
                                activeDocumentId)
                        .header("X-Tenant-Api-Key", tenant.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "不應修改",
                                  "content": "正式版不可直接修改。"
                                }
                                """))
                .andExpect(status().isConflict());

        JsonNode draft = json(mvc.perform(post(
                                "/api/v1/tenants/{tenantId}/datasets/{datasetId}/draft",
                                tenant.id(),
                                datasetId)
                        .header("X-Tenant-Api-Key", tenant.apiKey()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(2))
                .andReturn());
        String draftId = draft.path("id").asText();

        JsonNode draftDocuments = getJson(
                "/api/v1/tenants/" + tenant.id() + "/datasets/" + draftId + "/documents",
                tenant.apiKey());
        assertThat(draftDocuments).hasSize(1);
        String draftDocumentId = draftDocuments.get(0).path("id").asText();
        assertThat(draftDocumentId).isNotEqualTo(activeDocumentId);

        mvc.perform(put(
                                "/api/v1/tenants/{tenantId}/datasets/{datasetId}/documents/{documentId}",
                                tenant.id(),
                                draftId,
                                draftDocumentId)
                        .header("X-Tenant-Api-Key", tenant.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "付款方式（更新）",
                                  "content": "本店接受現金、信用卡及行動支付。"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("付款方式（更新）"))
                .andExpect(jsonPath("$.index_status").value("READY"));

        mvc.perform(delete(
                                "/api/v1/tenants/{tenantId}/datasets/{datasetId}/documents/{documentId}",
                                tenant.id(),
                                draftId,
                                draftDocumentId)
                        .header("X-Tenant-Api-Key", tenant.apiKey()))
                .andExpect(status().isNoContent());

        assertThat(getJson(
                        "/api/v1/tenants/" + tenant.id() + "/datasets/" + draftId + "/documents",
                        tenant.apiKey()))
                .isEmpty();
    }

    @Test
    void merchantOnboardingReturnsTheTenantApiKeyOnlyInTheCreationResponse()
            throws Exception {
        String slug = "portal-onboard-" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult onboard = mvc.perform(post("/portal/api/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "platform_admin_key": "%s",
                                  "tenant": {
                                    "name": "Portal onboarding",
                                    "slug": "%s",
                                    "timezone": "Asia/Taipei",
                                    "slot_minutes": 30
                                  }
                                }
                                """
                                .formatted(PLATFORM_KEY, slug)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.tenant.slug").value(slug))
                .andExpect(jsonPath("$.tenant_api_key").isNotEmpty())
                .andReturn();

        MockHttpSession session = (MockHttpSession) onboard.getRequest().getSession(false);
        mvc.perform(get("/portal/api/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.tenant_api_key").doesNotExist());
    }

    @Test
    void bookingIsIdempotentAndOnlyOneCustomerCanOwnASlot() throws Exception {
        Tenant tenant = createTenant("booking");
        String serviceId = firstId(
                getJson(
                        "/api/v1/tenants/" + tenant.id() + "/booking-services",
                        tenant.apiKey()));
        Instant slot = nextBusinessSlot();
        String request = """
                {
                  "service_id": "%s",
                  "line_user_id": "U-first",
                  "customer_name": "First customer",
                  "starts_at": "%s",
                  "idempotency_key": "booking-idempotency-0001"
                }
                """
                .formatted(serviceId, slot);

        JsonNode first = json(mvc.perform(post(
                                "/api/v1/tenants/{tenantId}/reservations", tenant.id())
                        .header("X-Tenant-Api-Key", tenant.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andReturn());
        JsonNode repeated = json(mvc.perform(post(
                                "/api/v1/tenants/{tenantId}/reservations", tenant.id())
                        .header("X-Tenant-Api-Key", tenant.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andReturn());

        assertThat(repeated.path("id").asText()).isEqualTo(first.path("id").asText());

        mvc.perform(post("/api/v1/tenants/{tenantId}/reservations", tenant.id())
                        .header("X-Tenant-Api-Key", tenant.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "service_id": "%s",
                                  "line_user_id": "U-second",
                                  "starts_at": "%s",
                                  "idempotency_key": "booking-idempotency-0002"
                                }
                                """
                                .formatted(serviceId, slot)))
                .andExpect(status().isConflict());
    }

    @Test
    void inactiveBookingServicesCannotBeReserved() throws Exception {
        Tenant tenant = createTenant("inactive-service");
        String serviceId = firstId(getJson(
                "/api/v1/tenants/" + tenant.id() + "/booking-services",
                tenant.apiKey()));
        jdbc.sql("""
                        update booking_services set active = false
                        where id = :serviceId and tenant_id = :tenantId
                        """)
                .param("serviceId", serviceId)
                .param("tenantId", tenant.id())
                .update();

        mvc.perform(post("/api/v1/tenants/{tenantId}/reservations", tenant.id())
                        .header("X-Tenant-Api-Key", tenant.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "service_id": "%s",
                                  "line_user_id": "U-inactive-service",
                                  "starts_at": "%s",
                                  "idempotency_key": "inactive-service-0001"
                                }
                                """
                                .formatted(serviceId, nextBusinessSlot())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Booking service is inactive"));
    }

    @Test
    void lineBookingPageUsesShortLivedIdentityAndRechecksTheSlot() throws Exception {
        Tenant tenant = createTenant("public-booking");
        String token = bookingAccessTokens.issue(tenant.id(), tenant.slug(), "U-booking-page");
        String authorization = "Bearer " + token;
        String serviceId = firstId(getJson(
                "/api/v1/tenants/" + tenant.id() + "/booking-services",
                tenant.apiKey()));
        Instant slot = nextBusinessSlot();

        mvc.perform(get("/booking/{tenantSlug}", tenant.slug()))
                .andExpect(status().isOk());
        mvc.perform(get("/booking/index.html"))
                .andExpect(status().isOk());
        mvc.perform(get("/booking/app.js"))
                .andExpect(status().isOk());
        mvc.perform(get("/booking/api/{tenantSlug}/bootstrap", tenant.slug())
                        .header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant_slug").value(tenant.slug()))
                .andExpect(jsonPath("$.services[0].id").value(serviceId));

        LocalDate slotDate = slot.atZone(ZoneId.of("Asia/Taipei")).toLocalDate();
        mvc.perform(get("/booking/api/{tenantSlug}/availability", tenant.slug())
                        .header("Authorization", authorization)
                        .queryParam("service_id", serviceId)
                        .queryParam("local_date", slotDate.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.local_date").value(slotDate.toString()))
                .andExpect(jsonPath("$.slots").isNotEmpty());

        mvc.perform(post("/booking/api/{tenantSlug}/reservations", tenant.slug())
                        .header("Authorization", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "service_id": "%s",
                                  "starts_at": "%s",
                                  "customer_name": "LINE customer",
                                  "idempotency_key": "web-booking-idempotency-0001"
                                }
                                """
                                .formatted(serviceId, slot)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.line_user_id").value("U-booking-page"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        mvc.perform(post("/booking/api/{tenantSlug}/reservations", tenant.slug())
                        .header(
                                "Authorization",
                                "Bearer " + bookingAccessTokens.issue(
                                        tenant.id(), tenant.slug(), "U-second-booking-page"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "service_id": "%s",
                                  "starts_at": "%s",
                                  "customer_name": "Second customer",
                                  "idempotency_key": "web-booking-idempotency-0002"
                                }
                                """
                                .formatted(serviceId, slot)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("The selected slot is no longer available"));

        mvc.perform(get("/booking/api/{tenantSlug}/bootstrap", tenant.slug())
                        .header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/booking/api/{tenantSlug}/bootstrap", tenant.slug()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void knowledgeRetrievalNeverCrossesTenantBoundary() throws Exception {
        Tenant firstTenant = createTenant("knowledge-a");
        Tenant secondTenant = createTenant("knowledge-b");
        String firstDataset = firstId(getJson(
                "/api/v1/tenants/" + firstTenant.id() + "/datasets",
                firstTenant.apiKey()));

        mvc.perform(post(
                                "/api/v1/tenants/{tenantId}/datasets/{datasetId}/documents",
                                firstTenant.id(),
                                firstDataset)
                        .header("X-Tenant-Api-Key", firstTenant.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "專屬通關密語",
                                  "content": "本商家的專屬通關密語是藍色海豚。",
                                  "source_url": "https://example.test/source"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.index_status").value("READY"));

        mvc.perform(post(
                                "/api/v1/tenants/{tenantId}/datasets/{datasetId}/publish",
                                firstTenant.id(),
                                firstDataset)
                        .header("X-Tenant-Api-Key", firstTenant.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mvc.perform(post("/api/v1/tenants/{tenantId}/ai/answer", firstTenant.id())
                        .header("X-Tenant-Api-Key", firstTenant.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "專屬通關密語是什麼？",
                                  "line_user_id": "U-first"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.answer").value(
                        org.hamcrest.Matchers.containsString("藍色海豚")));

        mvc.perform(post("/api/v1/tenants/{tenantId}/ai/answer", secondTenant.id())
                        .header("X-Tenant-Api-Key", secondTenant.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "專屬通關密語是什麼？",
                                  "line_user_id": "U-second"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grounded").value(false))
                .andExpect(jsonPath("$.citations").isEmpty());
    }

    @Test
    void lineWebhookVerifiesRawBodyDeduplicatesAndUsesSimulatedOutbox() throws Exception {
        Tenant tenant = createTenant("line");
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

        byte[] body = """
                {
                  "destination": "U-destination",
                  "events": [
                    {
                      "type": "message",
                      "webhookEventId": "01TESTEVENT000000000000000000",
                      "replyToken": "test-reply-token",
                      "source": {"type": "user", "userId": "U-line-user"},
                      "message": {"id": "1", "type": "text", "text": "人工客服"}
                    }
                  ]
                }
                """.getBytes(StandardCharsets.UTF_8);
        String signature = lineSignature(body, LINE_SECRET);

        mvc.perform(post("/webhooks/line/{tenantSlug}", tenant.slug())
                        .header("X-Line-Signature", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.duplicate").value(0));

        mvc.perform(post("/webhooks/line/{tenantSlug}", tenant.slug())
                        .header("X-Line-Signature", signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.duplicate").value(1));

        String eventId = lineRepository.findReadyEventIds(Instant.now().plusSeconds(1), 20)
                .stream()
                .filter(id -> lineRepository.findEvent(id)
                        .map(event -> event.tenantId().equals(tenant.id()))
                        .orElse(false))
                .findFirst()
                .orElseThrow();
        assertThat(lineRepository.claimEvent(eventId, Instant.now().plusSeconds(1))).isTrue();
        lineEventProcessor.process(eventId);

        assertThat(lineRepository.findEvent(eventId).orElseThrow().status())
                .isEqualTo("PROCESSED");
        String outboxStatus = jdbc.sql("""
                        select status from outbox_messages
                        where tenant_id = :tenantId
                        """)
                .param("tenantId", tenant.id())
                .query(String.class)
                .single();
        assertThat(outboxStatus).isEqualTo("SIMULATED");
    }

    @Test
    void staleEventRecoveryStopsAfterThreeClaims() throws Exception {
        Tenant tenant = createTenant("stale-event");
        String eventId = UUID.randomUUID().toString();
        Instant firstClaim = Instant.parse("2026-01-01T00:00:00Z");
        lineRepository.insertEvent(
                eventId,
                tenant.id(),
                "stale-webhook-event",
                "message",
                "U-stale",
                "{}",
                firstClaim.minusSeconds(1));

        for (int attempt = 1; attempt <= 3; attempt++) {
            Instant claimAt = firstClaim.plusSeconds(attempt * 10L);
            assertThat(lineRepository.claimEvent(eventId, claimAt))
                    .isTrue();
            lineRepository.recoverStaleEvents(
                    claimAt.plusSeconds(1),
                    claimAt.plusSeconds(2));
        }

        var event = lineRepository.findEvent(eventId).orElseThrow();
        assertThat(event.attempts()).isEqualTo(3);
        assertThat(event.status()).isEqualTo("FAILED");
        assertThat(lineRepository.findReadyEventIds(firstClaim.plusSeconds(10), 20))
                .doesNotContain(eventId);
    }

    @Test
    void processorRetriesWhenTenantBecomesUnavailable() throws Exception {
        Tenant tenant = createTenant("inactive-line-event");
        String eventId = UUID.randomUUID().toString();
        lineRepository.insertEvent(
                eventId,
                tenant.id(),
                "inactive-tenant-webhook-event",
                "message",
                "U-inactive",
                """
                {
                  "type": "message",
                  "replyToken": "inactive-reply-token",
                  "source": {"type": "user", "userId": "U-inactive"},
                  "message": {"id": "3", "type": "text", "text": "hello"}
                }
                """,
                Instant.now().minusSeconds(1));
        jdbc.sql("update tenants set active = false where id = :id")
                .param("id", tenant.id())
                .update();

        assertThat(lineRepository.claimEvent(eventId, Instant.now())).isTrue();
        lineEventProcessor.process(eventId);

        var event = lineRepository.findEvent(eventId).orElseThrow();
        assertThat(event.status()).isEqualTo("RETRY");
        assertThat(event.attempts()).isEqualTo(1);
    }

    @Test
    void retryUsesFailedReplyPayloadAsPushWithoutRegeneratingIt() throws Exception {
        Tenant tenant = createTenant("line-push-fallback");
        configureLineChannel(tenant);
        String eventId = UUID.randomUUID().toString();
        String replyToken = "expired-reply-token";
        String originalPayload = """
                [{"type":"text","text":"original generated answer"}]
                """.trim();
        String failedOutboxId = lineRepository.insertOutbox(
                tenant.id(),
                "U-push-user",
                replyToken,
                "REPLY",
                originalPayload,
                Instant.now().minusSeconds(10));
        lineRepository.markOutboxFailed(failedOutboxId, "reply response timed out");
        lineRepository.insertEvent(
                eventId,
                tenant.id(),
                "push-fallback-webhook-event",
                "message",
                "U-push-user",
                """
                {
                  "type": "message",
                  "replyToken": "%s",
                  "source": {"type": "user", "userId": "U-push-user"},
                  "message": {"id": "2", "type": "text", "text": "do not regenerate"}
                }
                """
                        .formatted(replyToken),
                Instant.now().minusSeconds(5));
        assertThat(lineRepository.claimEvent(eventId, Instant.now())).isTrue();
        lineRepository.releaseClaim(eventId, Instant.now(), "retry");
        assertThat(lineRepository.claimEvent(eventId, Instant.now())).isTrue();

        lineEventProcessor.process(eventId);

        assertThat(lineRepository.findEvent(eventId).orElseThrow().status())
                .isEqualTo("PROCESSED");
        var fallback = jdbc.sql("""
                        select delivery_type, reply_token, payload_json, status
                        from outbox_messages
                        where tenant_id = :tenantId and delivery_type = 'PUSH'
                        """)
                .param("tenantId", tenant.id())
                .query((rs, rowNum) -> new String[] {
                    rs.getString("delivery_type"),
                    rs.getString("reply_token"),
                    rs.getString("payload_json"),
                    rs.getString("status")
                })
                .single();
        assertThat(fallback[0]).isEqualTo("PUSH");
        assertThat(fallback[1]).isNull();
        assertThat(fallback[2]).isEqualTo(originalPayload);
        assertThat(fallback[3]).isEqualTo("SIMULATED");
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
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String slug = purpose + "-" + suffix;
        MvcResult result = mvc.perform(post("/api/v1/tenants")
                        .header("X-Platform-Admin-Key", PLATFORM_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Integration test",
                                  "slug": "%s",
                                  "timezone": "Asia/Taipei",
                                  "slot_minutes": 60
                                }
                                """
                                .formatted(slug)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode value = json(result);
        return new Tenant(
                value.path("id").asText(),
                value.path("slug").asText(),
                value.path("admin_api_key").asText());
    }

    private JsonNode getJson(String path, String tenantApiKey) throws Exception {
        return json(mvc.perform(get(path).header("X-Tenant-Api-Key", tenantApiKey))
                .andExpect(status().isOk())
                .andReturn());
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private String firstId(JsonNode array) {
        assertThat(array.isArray()).isTrue();
        assertThat(array.size()).isPositive();
        return array.get(0).path("id").asText();
    }

    private Instant nextBusinessSlot() {
        ZoneId zone = ZoneId.of("Asia/Taipei");
        ZonedDateTime now = ZonedDateTime.now(zone);
        LocalDate date = now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        return ZonedDateTime.of(date, LocalTime.of(10, 0), zone).toInstant();
    }

    private String lineSignature(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(body));
    }

    private record Tenant(String id, String slug, String apiKey) {}
}
