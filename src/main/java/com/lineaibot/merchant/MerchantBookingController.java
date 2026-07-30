package com.lineaibot.merchant;

import static com.lineaibot.merchant.MerchantDtos.AgendaResponse;
import static com.lineaibot.merchant.MerchantDtos.AvailabilityView;
import static com.lineaibot.merchant.MerchantDtos.BlockCreate;
import static com.lineaibot.merchant.MerchantDtos.BookingBlockView;
import static com.lineaibot.merchant.MerchantDtos.MerchantBookingBootstrap;
import static com.lineaibot.merchant.MerchantDtos.MerchantSessionView;
import static com.lineaibot.merchant.MerchantDtos.ServiceOption;
import static com.lineaibot.merchant.MerchantDtos.StaffView;

import com.lineaibot.booking.BookingManager;
import com.lineaibot.booking.BookingRepository;
import com.lineaibot.booking.BookingDtos.ReservationRead;
import com.lineaibot.shared.ApiException;
import com.lineaibot.tenant.TenantRepository;
import com.lineaibot.tenant.TenantRepository.TenantRow;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/merchant-booking/api/{tenantSlug}")
public class MerchantBookingController {

    private static final String TENANT_ID = "merchantBookingTenantId";
    private static final String STAFF_ID = "merchantBookingStaffId";
    private static final String CSRF_TOKEN = "merchantBookingCsrfToken";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final MerchantManageTokenService tokens;
    private final MerchantStaffService staffService;
    private final MerchantBookingService merchantBookings;
    private final BookingManager bookings;
    private final BookingRepository bookingRepository;
    private final TenantRepository tenants;

    public MerchantBookingController(
            MerchantManageTokenService tokens,
            MerchantStaffService staffService,
            MerchantBookingService merchantBookings,
            BookingManager bookings,
            BookingRepository bookingRepository,
            TenantRepository tenants) {
        this.tokens = tokens;
        this.staffService = staffService;
        this.merchantBookings = merchantBookings;
        this.bookings = bookings;
        this.bookingRepository = bookingRepository;
        this.tenants = tenants;
    }

    @PostMapping("/session")
    MerchantSessionView login(
            @PathVariable String tenantSlug,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String authorization,
            HttpServletRequest request) {
        var identity = tokens.consume(bearer(authorization));
        var tenant = requireTenant(identity.tenantId(), tenantSlug);
        var staff = staffService.requireActive(identity.tenantId(), identity.staffId());
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        byte[] random = new byte[32];
        SECURE_RANDOM.nextBytes(random);
        String csrf = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        session.setAttribute(TENANT_ID, tenant.id());
        session.setAttribute(STAFF_ID, staff.id());
        session.setAttribute(CSRF_TOKEN, csrf);
        session.setMaxInactiveInterval(60 * 60);
        return sessionView(tenant, staff, csrf);
    }

    @GetMapping("/session")
    MerchantSessionView session(
            @PathVariable String tenantSlug, HttpSession session) {
        try {
            Context context = requireContext(tenantSlug, session);
            return sessionView(
                    context.tenant(),
                    context.staff(),
                    (String) session.getAttribute(CSRF_TOKEN));
        } catch (ApiException exception) {
            return new MerchantSessionView(false, null, tenantSlug, null, null, null);
        }
    }

    @DeleteMapping("/session")
    void logout(HttpSession session) {
        session.removeAttribute(TENANT_ID);
        session.removeAttribute(STAFF_ID);
        session.removeAttribute(CSRF_TOKEN);
    }

    @GetMapping("/bootstrap")
    MerchantBookingBootstrap bootstrap(
            @PathVariable String tenantSlug, HttpSession session) {
        Context context = requireContext(tenantSlug, session);
        return new MerchantBookingBootstrap(
                context.tenant().name(),
                context.tenant().slug(),
                context.tenant().timezone(),
                context.tenant().slotMinutes(),
                context.staff(),
                bookingRepository.findActiveServices(context.tenant().id()).stream()
                        .map(service -> new ServiceOption(service.id(), service.name()))
                        .toList());
    }

    @GetMapping("/agenda")
    AgendaResponse agenda(
            @PathVariable String tenantSlug,
            HttpSession session,
            @RequestParam Instant from,
            @RequestParam Instant to) {
        Context context = requireContext(tenantSlug, session);
        return merchantBookings.agenda(context.tenant().id(), from, to);
    }

    @GetMapping("/agenda/local")
    AgendaResponse localAgenda(
            @PathVariable String tenantSlug,
            HttpSession session,
            @RequestParam(name = "from_date") LocalDate fromDate,
            @RequestParam(name = "to_date") LocalDate toDateExclusive) {
        Context context = requireContext(tenantSlug, session);
        if (!toDateExclusive.isAfter(fromDate)
                || toDateExclusive.isAfter(fromDate.plusDays(93))) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "Agenda date range is invalid");
        }
        return merchantBookings.agendaForLocalDates(
                context.tenant(), fromDate, toDateExclusive);
    }

    @GetMapping("/availability")
    AvailabilityView availability(
            @PathVariable String tenantSlug,
            HttpSession session,
            @RequestParam(name = "service_id") String serviceId,
            @RequestParam(name = "local_date") LocalDate localDate) {
        Context context = requireContext(tenantSlug, session);
        return new AvailabilityView(
                localDate,
                context.tenant().timezone(),
                bookings.listAvailableSlots(context.tenant(), serviceId, localDate));
    }

    @PostMapping("/reservations/{reservationId}/cancel")
    ReservationRead cancel(
            @PathVariable String tenantSlug,
            @PathVariable String reservationId,
            HttpSession session,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfToken) {
        requireCsrf(session, csrfToken);
        Context context = requireContext(tenantSlug, session);
        return merchantBookings.cancel(
                context.tenant().id(), reservationId, context.staff());
    }

    @PostMapping("/blocks")
    BookingBlockView block(
            @PathVariable String tenantSlug,
            HttpSession session,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfToken,
            @Valid @RequestBody BlockCreate request) {
        requireCsrf(session, csrfToken);
        if (request.startsAt() == null) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "starts_at must include a timezone offset");
        }
        Context context = requireContext(tenantSlug, session);
        return merchantBookings.block(
                context.tenant(),
                request.startsAt().toInstant(),
                request.reason(),
                context.staff());
    }

    @DeleteMapping("/blocks/{blockId}")
    BookingBlockView releaseBlock(
            @PathVariable String tenantSlug,
            @PathVariable String blockId,
            HttpSession session,
            @RequestHeader(name = "X-CSRF-Token", required = false) String csrfToken) {
        requireCsrf(session, csrfToken);
        Context context = requireContext(tenantSlug, session);
        return merchantBookings.releaseBlock(
                context.tenant().id(), blockId, context.staff());
    }

    private Context requireContext(String tenantSlug, HttpSession session) {
        Object tenantId = session.getAttribute(TENANT_ID);
        Object staffId = session.getAttribute(STAFF_ID);
        if (!(tenantId instanceof String id) || !(staffId instanceof String staff)) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED, "Merchant booking session is required");
        }
        TenantRow tenant = requireTenant(id, tenantSlug);
        return new Context(tenant, staffService.requireActive(id, staff));
    }

    private TenantRow requireTenant(String tenantId, String tenantSlug) {
        return tenants.findById(tenantId)
                .filter(TenantRow::active)
                .filter(tenant -> tenant.slug().equals(tenantSlug))
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNAUTHORIZED, "Merchant booking tenant is invalid"));
    }

    private MerchantSessionView sessionView(
            TenantRow tenant, StaffView staff, String csrfToken) {
        return new MerchantSessionView(
                true,
                tenant.name(),
                tenant.slug(),
                tenant.timezone(),
                staff,
                csrfToken);
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

    private String bearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring("Bearer ".length()).strip();
    }

    private record Context(TenantRow tenant, StaffView staff) {}
}
