package com.lineaibot.booking;

import com.lineaibot.booking.BookingDtos.AvailabilityResponse;
import com.lineaibot.booking.BookingDtos.PublicBookingBootstrap;
import com.lineaibot.booking.BookingDtos.PublicReservationCreate;
import com.lineaibot.booking.BookingDtos.ReservationCreate;
import com.lineaibot.booking.BookingDtos.ReservationRead;
import com.lineaibot.shared.ApiException;
import com.lineaibot.tenant.TenantRepository;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/booking/api/{tenantSlug}")
public class PublicBookingController {

    private final BookingAccessTokenService accessTokens;
    private final BookingManager bookings;
    private final BookingRepository bookingRepository;
    private final TenantRepository tenants;

    public PublicBookingController(
            BookingAccessTokenService accessTokens,
            BookingManager bookings,
            BookingRepository bookingRepository,
            TenantRepository tenants) {
        this.accessTokens = accessTokens;
        this.bookings = bookings;
        this.bookingRepository = bookingRepository;
        this.tenants = tenants;
    }

    @GetMapping("/bootstrap")
    PublicBookingBootstrap bootstrap(
            @PathVariable String tenantSlug,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String authorization) {
        var tenant = requireTenant(tenantSlug, authorization);
        return new PublicBookingBootstrap(
                tenant.name(),
                tenant.slug(),
                tenant.timezone(),
                tenant.slotMinutes(),
                bookingRepository.findActiveServices(tenant.id()).stream()
                        .map(service -> new BookingDtos.PublicBookingService(
                                service.id(), service.name(), service.description()))
                        .toList());
    }

    @GetMapping("/availability")
    AvailabilityResponse availability(
            @PathVariable String tenantSlug,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String authorization,
            @RequestParam(name = "service_id") String serviceId,
            @RequestParam(name = "local_date") LocalDate localDate) {
        var tenant = requireTenant(tenantSlug, authorization);
        return new AvailabilityResponse(
                tenant.id(),
                serviceId,
                localDate,
                tenant.timezone(),
                bookings.listAvailableSlots(tenant, serviceId, localDate));
    }

    @PostMapping("/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    ReservationRead createReservation(
            @PathVariable String tenantSlug,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                    String authorization,
            @Valid @RequestBody PublicReservationCreate request) {
        var identity = accessTokens.require(tenantSlug, bearer(authorization));
        var tenant = requireMatchingTenant(identity);
        return bookings.createReservation(
                tenant,
                new ReservationCreate(
                        request.serviceId(),
                        identity.lineUserId(),
                        request.startsAt(),
                        request.customerName(),
                        request.idempotencyKey() == null
                                ? "web:" + UUID.randomUUID()
                                : request.idempotencyKey()));
    }

    private TenantRepository.TenantRow requireTenant(String tenantSlug, String authorization) {
        return requireMatchingTenant(accessTokens.require(tenantSlug, bearer(authorization)));
    }

    private TenantRepository.TenantRow requireMatchingTenant(
            BookingAccessTokenService.BookingIdentity identity) {
        return tenants.findActiveBySlug(identity.tenantSlug())
                .filter(tenant -> tenant.id().equals(identity.tenantId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Tenant not found"));
    }

    private String bearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED, "Booking link is invalid or expired");
        }
        return authorization.substring("Bearer ".length()).trim();
    }
}
