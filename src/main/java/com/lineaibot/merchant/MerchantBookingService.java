package com.lineaibot.merchant;

import static com.lineaibot.merchant.MerchantDtos.AgendaResponse;
import static com.lineaibot.merchant.MerchantDtos.BookingBlockView;
import static com.lineaibot.merchant.MerchantDtos.ReservationSummary;

import com.lineaibot.booking.BookingManager;
import com.lineaibot.booking.BookingRepository;
import com.lineaibot.booking.BookingDtos.ReservationRead;
import com.lineaibot.shared.ApiException;
import com.lineaibot.tenant.TenantRepository;
import com.lineaibot.tenant.TenantRepository.TenantRow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class MerchantBookingService {

    private final BookingRepository repository;
    private final BookingManager bookings;
    private final TenantRepository tenants;

    public MerchantBookingService(
            BookingRepository repository,
            BookingManager bookings,
            TenantRepository tenants) {
        this.repository = repository;
        this.bookings = bookings;
        this.tenants = tenants;
    }

    public TenantRow requireTenant(String tenantId) {
        return tenants.findById(tenantId)
                .filter(TenantRow::active)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNAUTHORIZED, "Merchant tenant is unavailable"));
    }

    public AgendaResponse agenda(String tenantId, Instant from, Instant to) {
        if (from == null || to == null || !to.isAfter(from)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "Agenda time range is invalid");
        }
        if (to.isAfter(from.plusSeconds(93L * 24 * 60 * 60))) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "Agenda range cannot exceed 93 days");
        }
        List<ReservationSummary> reservations = repository.findBetween(tenantId, from, to)
                .stream()
                .map(row -> new ReservationSummary(
                        row.id(),
                        row.serviceId(),
                        row.serviceName(),
                        blankFallback(row.customerName(), "未填姓名"),
                        row.startsAt(),
                        row.endsAt(),
                        row.status(),
                        row.createdAt()))
                .toList();
        List<BookingBlockView> blocks = repository.findBlocksBetween(tenantId, from, to)
                .stream()
                .map(this::toBlockView)
                .toList();
        return new AgendaResponse(from, to, reservations, blocks);
    }

    public AgendaResponse agendaForLocalDates(
            TenantRow tenant, LocalDate fromDate, LocalDate toDateExclusive) {
        ZoneId zone = ZoneId.of(tenant.timezone());
        return agenda(
                tenant.id(),
                fromDate.atStartOfDay(zone).toInstant(),
                toDateExclusive.atStartOfDay(zone).toInstant());
    }

    public ReservationRead cancel(
            String tenantId, String reservationId, MerchantDtos.StaffView staff) {
        requireMutationPermission(staff);
        return bookings.cancelReservationAsStaff(tenantId, reservationId, staff.id());
    }

    public BookingBlockView block(
            TenantRow tenant,
            Instant startsAt,
            String reason,
            MerchantDtos.StaffView staff) {
        requireMutationPermission(staff);
        return toBlockView(bookings.blockSlot(tenant, startsAt, reason, staff.id()));
    }

    public BookingBlockView releaseBlock(
            String tenantId, String blockId, MerchantDtos.StaffView staff) {
        requireMutationPermission(staff);
        return toBlockView(bookings.releaseBlock(tenantId, blockId, staff.id()));
    }

    public ReservationSummary requireReservationSummary(
            String tenantId, String reservationId) {
        var reservation = bookings.requireReservation(tenantId, reservationId);
        var service = repository.findService(tenantId, reservation.serviceId())
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "Booking service not found"));
        return new ReservationSummary(
                reservation.id(),
                reservation.serviceId(),
                service.name(),
                blankFallback(reservation.customerName(), "未填姓名"),
                reservation.startsAt(),
                reservation.endsAt(),
                reservation.status(),
                reservation.createdAt());
    }

    private void requireMutationPermission(MerchantDtos.StaffView staff) {
        if (!"OWNER".equals(staff.role()) && !"MANAGER".equals(staff.role())) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN, "This staff role cannot change bookings");
        }
    }

    private BookingBlockView toBlockView(BookingRepository.BookingBlockRow row) {
        return new BookingBlockView(
                row.id(),
                row.startsAt(),
                row.endsAt(),
                row.reason(),
                row.active(),
                row.createdByStaffId(),
                row.createdAt(),
                row.releasedAt());
    }

    private String blankFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
