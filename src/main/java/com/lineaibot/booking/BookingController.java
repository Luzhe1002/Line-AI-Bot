package com.lineaibot.booking;

import com.lineaibot.booking.BookingDtos.AvailabilityResponse;
import com.lineaibot.booking.BookingDtos.ReservationCreate;
import com.lineaibot.booking.BookingDtos.ReservationRead;
import com.lineaibot.shared.ApiAuthService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
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
@RequestMapping("/api/v1/tenants/{tenantId}")
public class BookingController {

    private final BookingManager bookings;
    private final ApiAuthService auth;

    public BookingController(BookingManager bookings, ApiAuthService auth) {
        this.bookings = bookings;
        this.auth = auth;
    }

    @GetMapping("/availability")
    AvailabilityResponse availability(
            @PathVariable String tenantId,
            @RequestHeader(name = "X-Tenant-Api-Key", required = false) String apiKey,
            @RequestParam(name = "service_id") String serviceId,
            @RequestParam(name = "local_date") LocalDate localDate) {
        var tenant = auth.requireTenantAdmin(tenantId, apiKey);
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
            @PathVariable String tenantId,
            @RequestHeader(name = "X-Tenant-Api-Key", required = false) String apiKey,
            @Valid @RequestBody ReservationCreate request) {
        return bookings.createReservation(auth.requireTenantAdmin(tenantId, apiKey), request);
    }

    @GetMapping("/reservations")
    List<ReservationRead> listReservations(
            @PathVariable String tenantId,
            @RequestHeader(name = "X-Tenant-Api-Key", required = false) String apiKey) {
        var tenant = auth.requireTenantAdmin(tenantId, apiKey);
        return bookings.listReservations(tenant.id());
    }

    @PostMapping("/reservations/{reservationId}/cancel")
    ReservationRead cancelReservation(
            @PathVariable String tenantId,
            @PathVariable String reservationId,
            @RequestHeader(name = "X-Tenant-Api-Key", required = false) String apiKey) {
        var tenant = auth.requireTenantAdmin(tenantId, apiKey);
        return bookings.cancelReservation(tenant.id(), reservationId, null);
    }
}
