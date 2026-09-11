package com.lineaibot.tenant;

import com.lineaibot.shared.ApiAuthService;
import com.lineaibot.tenant.TenantDtos.BookingServiceCreate;
import com.lineaibot.tenant.TenantDtos.BookingServiceRead;
import com.lineaibot.tenant.TenantDtos.BusinessHourRead;
import com.lineaibot.tenant.TenantDtos.BusinessHourUpsert;
import com.lineaibot.tenant.TenantDtos.LineChannelRead;
import com.lineaibot.tenant.TenantDtos.LineChannelUpsert;
import com.lineaibot.tenant.TenantDtos.TenantCreate;
import com.lineaibot.tenant.TenantDtos.TenantCreated;
import com.lineaibot.tenant.TenantDtos.TenantRead;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

    private final TenantService service;
    private final TenantRepository repository;
    private final ApiAuthService auth;

    public TenantController(
            TenantService service, TenantRepository repository, ApiAuthService auth) {
        this.service = service;
        this.repository = repository;
        this.auth = auth;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TenantCreated createTenant(
            @RequestHeader(name = "X-Platform-Admin-Key", required = false) String apiKey,
            @Valid @RequestBody TenantCreate request) {
        auth.requirePlatformAdmin(apiKey);
        return service.createTenant(request);
    }

    @GetMapping
    List<TenantRead> listTenants(
            @RequestHeader(name = "X-Platform-Admin-Key", required = false) String apiKey) {
        auth.requirePlatformAdmin(apiKey);
        return repository.findAll();
    }

    @GetMapping("/{tenantId}")
    TenantRead getTenant(
            @PathVariable String tenantId,
            @RequestHeader(name = "X-Tenant-Api-Key", required = false) String apiKey) {
        return auth.requireTenantAdmin(tenantId, apiKey).toRead();
    }

    @PutMapping("/{tenantId}/line-channel")
    LineChannelRead configureLineChannel(
            @PathVariable String tenantId,
            @RequestHeader(name = "X-Tenant-Api-Key", required = false) String apiKey,
            @Valid @RequestBody LineChannelUpsert request) {
        return service.configureLineChannel(
                auth.requireTenantAdmin(tenantId, apiKey), request);
    }

    @GetMapping("/{tenantId}/line-channel")
    LineChannelRead getLineChannel(
            @PathVariable String tenantId,
            @RequestHeader(name = "X-Tenant-Api-Key", required = false) String apiKey) {
        return service.getLineChannel(auth.requireTenantAdmin(tenantId, apiKey));
    }

    @PutMapping("/{tenantId}/business-hours")
    BusinessHourRead saveBusinessHour(
            @PathVariable String tenantId,
            @RequestHeader(name = "X-Tenant-Api-Key", required = false) String apiKey,
            @Valid @RequestBody BusinessHourUpsert request) {
        return service.saveBusinessHour(
                auth.requireTenantAdmin(tenantId, apiKey), request);
    }

    @GetMapping("/{tenantId}/business-hours")
    List<BusinessHourRead> listBusinessHours(
            @PathVariable String tenantId,
            @RequestHeader(name = "X-Tenant-Api-Key", required = false) String apiKey) {
        return service.listBusinessHours(auth.requireTenantAdmin(tenantId, apiKey));
    }

    @PostMapping("/{tenantId}/booking-services")
    @ResponseStatus(HttpStatus.CREATED)
    BookingServiceRead createBookingService(
            @PathVariable String tenantId,
            @RequestHeader(name = "X-Tenant-Api-Key", required = false) String apiKey,
            @Valid @RequestBody BookingServiceCreate request) {
        return service.createBookingService(
                auth.requireTenantAdmin(tenantId, apiKey), request);
    }

    @GetMapping("/{tenantId}/booking-services")
    List<BookingServiceRead> listBookingServices(
            @PathVariable String tenantId,
            @RequestHeader(name = "X-Tenant-Api-Key", required = false) String apiKey) {
        return service.listBookingServices(auth.requireTenantAdmin(tenantId, apiKey));
    }
}
