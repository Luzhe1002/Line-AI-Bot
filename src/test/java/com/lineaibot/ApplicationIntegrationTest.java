package com.lineaibot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lineaibot.line.LineEventProcessor;
import com.lineaibot.line.LineRepository;
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
    private JdbcClient jdbc;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
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
