package com.lineaibot.tenant;

import com.lineaibot.config.AppProperties;
import com.lineaibot.shared.ApiException;
import com.lineaibot.shared.CryptoService;
import com.lineaibot.tenant.TenantDtos.BookingServiceCreate;
import com.lineaibot.tenant.TenantDtos.BookingServiceRead;
import com.lineaibot.tenant.TenantDtos.BusinessHourRead;
import com.lineaibot.tenant.TenantDtos.BusinessHourUpsert;
import com.lineaibot.tenant.TenantDtos.LineChannelRead;
import com.lineaibot.tenant.TenantDtos.LineChannelUpsert;
import com.lineaibot.tenant.TenantDtos.TenantCreate;
import com.lineaibot.tenant.TenantDtos.TenantCreated;
import com.lineaibot.tenant.TenantRepository.TenantRow;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantService {

    private static final Set<Integer> ALLOWED_SLOT_MINUTES =
            Set.of(15, 20, 30, 45, 60, 90, 120);

    private final TenantRepository repository;
    private final CryptoService crypto;
    private final AppProperties properties;

    public TenantService(
            TenantRepository repository, CryptoService crypto, AppProperties properties) {
        this.repository = repository;
        this.crypto = crypto;
        this.properties = properties;
    }

    @Transactional
    public TenantCreated createTenant(TenantCreate request) {
        String timezone = request.timezone() == null || request.timezone().isBlank()
                ? "Asia/Taipei"
                : request.timezone();
        int slotMinutes = request.slotMinutes() == null ? 60 : request.slotMinutes();
        validateTimezone(timezone);
        if (!ALLOWED_SLOT_MINUTES.contains(slotMinutes)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "slot_minutes must be one of 15, 20, 30, 45, 60, 90, 120");
        }

        String apiKey = crypto.generateApiKey();
        Instant now = Instant.now();
        TenantRow row = new TenantRow(
                UUID.randomUUID().toString(),
                request.slug(),
                request.name(),
                timezone,
                slotMinutes,
                crypto.hashApiKey(apiKey),
                true,
                now);
        try {
            repository.insertTenant(row);
            for (int weekday = 0; weekday < 5; weekday++) {
                repository.insertDefaultBusinessHour(
                        UUID.randomUUID().toString(),
                        row.id(),
                        weekday,
                        LocalTime.of(9, 0),
                        LocalTime.of(18, 0));
            }
            repository.insertDefaultBookingService(
                    UUID.randomUUID().toString(), row.id(), now);
            repository.insertDefaultDataset(UUID.randomUUID().toString(), row.id(), now);
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "Tenant slug already exists");
        }
        return new TenantCreated(
                row.id(),
                row.name(),
                row.slug(),
                row.timezone(),
                row.slotMinutes(),
                row.active(),
                row.createdAt(),
                apiKey);
    }

    @Transactional
    public LineChannelRead configureLineChannel(
            TenantRow tenant, LineChannelUpsert request) {
        boolean enabled = request.enabled() == null || request.enabled();
        repository.saveLineChannel(
                tenant.id(),
                crypto.encryptSecret(properties.getEncryptionKey(), request.channelSecret()),
                crypto.encryptSecret(
                        properties.getEncryptionKey(), request.channelAccessToken()),
                enabled,
                Instant.now());
        return lineChannelView(tenant, true, enabled);
    }

    public LineChannelRead getLineChannel(TenantRow tenant) {
        return repository.findLineChannel(tenant.id())
                .map(channel -> lineChannelView(tenant, true, channel.enabled()))
                .orElseGet(() -> lineChannelView(tenant, false, false));
    }

    @Transactional
    public BusinessHourRead saveBusinessHour(TenantRow tenant, BusinessHourUpsert request) {
        if (request.openTime() == null || request.closeTime() == null
                || !request.openTime().isBefore(request.closeTime())) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "open_time must be before close_time");
        }
        return repository.saveBusinessHour(
                tenant.id(),
                request.weekday(),
                request.openTime(),
                request.closeTime(),
                request.active() == null || request.active());
    }

    public List<BusinessHourRead> listBusinessHours(TenantRow tenant) {
        return repository.findBusinessHours(tenant.id());
    }

    @Transactional
    public BookingServiceRead createBookingService(
            TenantRow tenant, BookingServiceCreate request) {
        try {
            return repository.insertBookingService(
                    tenant.id(), request.name(), request.description(), Instant.now());
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "A booking service with this name already exists");
        }
    }

    public List<BookingServiceRead> listBookingServices(TenantRow tenant) {
        return repository.findBookingServices(tenant.id());
    }

    private LineChannelRead lineChannelView(
            TenantRow tenant, boolean configured, boolean enabled) {
        return new LineChannelRead(
                tenant.id(),
                configured,
                enabled,
                properties.getPublicBaseUrl().replaceAll("/+$", "")
                        + "/webhooks/line/"
                        + tenant.slug());
    }

    private void validateTimezone(String timezone) {
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown IANA timezone");
        }
    }
}
