package com.lineaibot.portal;

import com.lineaibot.knowledge.KnowledgeDtos.AnswerRequest;
import com.lineaibot.knowledge.KnowledgeDtos.AnswerResponse;
import com.lineaibot.knowledge.KnowledgeDtos.DatasetCreate;
import com.lineaibot.knowledge.KnowledgeDtos.DatasetRead;
import com.lineaibot.knowledge.KnowledgeDtos.KnowledgeDocumentCreate;
import com.lineaibot.knowledge.KnowledgeDtos.KnowledgeDocumentRead;
import com.lineaibot.knowledge.KnowledgeDtos.ReindexResponse;
import com.lineaibot.knowledge.KnowledgeService;
import com.lineaibot.shared.ApiAuthService;
import com.lineaibot.shared.ApiException;
import com.lineaibot.tenant.TenantDtos.BookingServiceCreate;
import com.lineaibot.tenant.TenantDtos.BookingServiceRead;
import com.lineaibot.tenant.TenantDtos.BusinessHourRead;
import com.lineaibot.tenant.TenantDtos.BusinessHourUpsert;
import com.lineaibot.tenant.TenantDtos.LineChannelRead;
import com.lineaibot.tenant.TenantDtos.LineChannelUpsert;
import com.lineaibot.tenant.TenantDtos.TenantCreate;
import com.lineaibot.tenant.TenantDtos.TenantRead;
import com.lineaibot.tenant.TenantRepository;
import com.lineaibot.tenant.TenantRepository.TenantRow;
import com.lineaibot.tenant.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/portal/api")
public class PortalController {

    private static final String TENANT_ID = "portalTenantId";
    private static final String CSRF_TOKEN = "portalCsrfToken";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ApiAuthService apiAuth;
    private final TenantService tenants;
    private final TenantRepository tenantRepository;
    private final KnowledgeService knowledge;

    public PortalController(
            ApiAuthService apiAuth,
            TenantService tenants,
            TenantRepository tenantRepository,
            KnowledgeService knowledge) {
        this.apiAuth = apiAuth;
        this.tenants = tenants;
        this.tenantRepository = tenantRepository;
        this.knowledge = knowledge;
    }

    public record LoginRequest(@NotBlank String tenantId, @NotBlank String apiKey) {}

    public record OnboardingRequest(
            @NotBlank String platformAdminKey,
            @NotNull @Valid TenantCreate tenant) {}

    public record SessionView(
            boolean authenticated,
            TenantRead tenant,
            String csrfToken,
            String tenantApiKey) {}

    public record Overview(
            TenantRead tenant,
            LineChannelRead lineChannel,
            List<BusinessHourRead> businessHours,
            List<BookingServiceRead> bookingServices,
            List<DatasetRead> datasets) {}

    @PostMapping("/session")
    SessionView login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        TenantRow tenant = apiAuth.requireTenantAdmin(request.tenantId(), request.apiKey());
        return establishSession(httpRequest, tenant);
    }

    @PostMapping("/onboarding")
    SessionView onboard(
            @Valid @RequestBody OnboardingRequest request,
            HttpServletRequest httpRequest) {
        apiAuth.requirePlatformAdmin(request.platformAdminKey());
        var created = tenants.createTenant(request.tenant());
        TenantRow tenant = tenantRepository.findById(created.id()).orElseThrow();
        return establishSession(httpRequest, tenant, created.adminApiKey());
    }

    @GetMapping("/session")
    SessionView session(HttpSession session) {
        Object tenantId = session.getAttribute(TENANT_ID);
        if (!(tenantId instanceof String id)) {
            return new SessionView(false, null, null, null);
        }
        TenantRow tenant = tenantRepository.findById(id).orElse(null);
        if (tenant == null || !tenant.active()) {
            session.invalidate();
            return new SessionView(false, null, null, null);
        }
        return new SessionView(
                true, tenant.toRead(), (String) session.getAttribute(CSRF_TOKEN), null);
    }

    @DeleteMapping("/session")
    void logout(HttpSession session) {
        session.invalidate();
    }

    @GetMapping("/overview")
    Overview overview(HttpSession session) {
        TenantRow tenant = requireTenant(session);
        return new Overview(
                tenant.toRead(),
                tenants.getLineChannel(tenant),
                tenants.listBusinessHours(tenant),
                tenants.listBookingServices(tenant),
                knowledge.listDatasets(tenant));
    }

    @PutMapping("/line-channel")
    LineChannelRead saveLineChannel(
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfToken,
            HttpSession session,
            @Valid @RequestBody LineChannelUpsert request) {
        requireCsrf(session, csrfToken);
        return tenants.configureLineChannel(requireTenant(session), request);
    }

    @PutMapping("/business-hours")
    BusinessHourRead saveBusinessHour(
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfToken,
            HttpSession session,
            @Valid @RequestBody BusinessHourUpsert request) {
        requireCsrf(session, csrfToken);
        return tenants.saveBusinessHour(requireTenant(session), request);
    }

    @PostMapping("/booking-services")
    @ResponseStatus(HttpStatus.CREATED)
    BookingServiceRead createBookingService(
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfToken,
            HttpSession session,
            @Valid @RequestBody BookingServiceCreate request) {
        requireCsrf(session, csrfToken);
        return tenants.createBookingService(requireTenant(session), request);
    }

    @PostMapping("/datasets")
    @ResponseStatus(HttpStatus.CREATED)
    DatasetRead createDataset(
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfToken,
            HttpSession session,
            @Valid @RequestBody DatasetCreate request) {
        requireCsrf(session, csrfToken);
        return knowledge.createDataset(requireTenant(session), request);
    }

    @GetMapping("/documents")
    List<KnowledgeDocumentRead> documents(
            HttpSession session, @RequestParam String datasetId) {
        return knowledge.listDocuments(requireTenant(session), datasetId);
    }

    @PostMapping("/documents")
    @ResponseStatus(HttpStatus.CREATED)
    KnowledgeDocumentRead addDocument(
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfToken,
            HttpSession session,
            @RequestParam String datasetId,
            @Valid @RequestBody KnowledgeDocumentCreate request) {
        requireCsrf(session, csrfToken);
        return knowledge.addDocument(requireTenant(session), datasetId, request);
    }

    @PostMapping("/documents/upload")
    @ResponseStatus(HttpStatus.CREATED)
    KnowledgeDocumentRead uploadDocument(
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfToken,
            HttpSession session,
            @RequestParam String datasetId,
            @RequestParam(required = false) String title,
            @RequestParam MultipartFile file) {
        requireCsrf(session, csrfToken);
        if (file.isEmpty() || file.getSize() > 1_000_000) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Upload must contain a file no larger than 1 MB");
        }
        String filename = file.getOriginalFilename() == null
                ? "uploaded-document.txt"
                : file.getOriginalFilename();
        filename = filename.replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1);
        String lower = filename.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".txt")
                || lower.endsWith(".md")
                || lower.endsWith(".csv"))) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Only UTF-8 TXT, Markdown, and CSV files are supported");
        }
        String content;
        try {
            content = StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(file.getBytes()))
                    .toString();
        } catch (Exception exception) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "Upload must be valid UTF-8 text");
        }
        if (content.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Upload is empty");
        }
        if (content.length() > 100_000) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Upload contains more than 100,000 characters");
        }
        String documentTitle = title == null || title.isBlank() ? filename : title.strip();
        if (documentTitle.isBlank() || documentTitle.length() > 240) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Document title must contain 1 to 240 characters");
        }
        return knowledge.addDocument(
                requireTenant(session),
                datasetId,
                new KnowledgeDocumentCreate(documentTitle, content, null));
    }

    @PostMapping("/datasets/publish")
    DatasetRead publish(
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfToken,
            HttpSession session,
            @RequestParam String datasetId) {
        requireCsrf(session, csrfToken);
        return knowledge.publishDataset(requireTenant(session), datasetId);
    }

    @PostMapping("/datasets/reindex")
    ReindexResponse reindex(
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfToken,
            HttpSession session,
            @RequestParam String datasetId) {
        requireCsrf(session, csrfToken);
        return knowledge.reindex(requireTenant(session), datasetId);
    }

    @PostMapping("/answer")
    AnswerResponse answer(
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfToken,
            HttpSession session,
            @RequestParam String datasetId,
            @Valid @RequestBody AnswerRequest request) {
        requireCsrf(session, csrfToken);
        return knowledge.previewAnswer(
                requireTenant(session), datasetId, request.question());
    }

    private SessionView establishSession(
            HttpServletRequest request, TenantRow tenant) {
        return establishSession(request, tenant, null);
    }

    private SessionView establishSession(
            HttpServletRequest request, TenantRow tenant, String tenantApiKey) {
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        byte[] random = new byte[32];
        SECURE_RANDOM.nextBytes(random);
        String csrfToken = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        session.setAttribute(TENANT_ID, tenant.id());
        session.setAttribute(CSRF_TOKEN, csrfToken);
        session.setMaxInactiveInterval(8 * 60 * 60);
        return new SessionView(true, tenant.toRead(), csrfToken, tenantApiKey);
    }

    private TenantRow requireTenant(HttpSession session) {
        Object tenantId = session.getAttribute(TENANT_ID);
        if (!(tenantId instanceof String id)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Merchant session is required");
        }
        TenantRow tenant = tenantRepository.findById(id)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNAUTHORIZED, "Merchant session is invalid"));
        if (!tenant.active()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Merchant account is inactive");
        }
        return tenant;
    }

    private void requireCsrf(HttpSession session, String supplied) {
        Object expected = session.getAttribute(CSRF_TOKEN);
        if (!(expected instanceof String token)
                || supplied == null
                || !java.security.MessageDigest.isEqual(
                        token.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Invalid CSRF token");
        }
    }
}
