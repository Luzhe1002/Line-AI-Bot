package com.lineaibot.line;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.lineaibot.booking.BookingManager;
import com.lineaibot.booking.BookingDtos.ReservationRead;
import com.lineaibot.tenant.TenantRepository.TenantRow;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationCancellationTest {
    private final BookingManager bookings = mock(BookingManager.class);
    private final ConversationService service = new ConversationService(bookings, null, null, null, null, null, null, null);
    private final TenantRow tenant = new TenantRow("tenant", "test", "Test", "Asia/Taipei", 60, "hash", true, Instant.now());
    private final ReservationRead reservation = new ReservationRead("r1", "tenant", "service", "user", "客人",
            Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200), "CONFIRMED", "key", Instant.now(), null);

    @Test
    void selectionRequiresExplicitMatchingConfirmation() {
        when(bookings.upcomingReservations("tenant", "user", 100)).thenReturn(List.of(reservation));
        var selected = service.handlePostback(tenant, "user", "action=cancel&reservation_id=r1", "event", ConversationContext.History.empty());
        verify(bookings, never()).cancelReservation(anyString(), anyString(), anyString());
        assertThat(selected.state().confirmationToken()).isNotBlank();
        var history = new ConversationContext.History(List.of(new ConversationContext.Turn("", "", selected.state())));
        var invalid = service.handlePostback(tenant, "user", "action=cancel_confirm&reservation_id=r1&confirmation=wrong", "event2", history);
        assertThat(invalid.messages().getFirst().get("text").toString()).contains("已失效");
        verify(bookings, never()).cancelReservation(anyString(), anyString(), anyString());
        when(bookings.cancelReservation("tenant", "r1", "user")).thenReturn(reservation);
        var result = service.handlePostback(tenant, "user", "action=cancel_confirm&reservation_id=r1&confirmation=" + selected.state().confirmationToken(), "event3", history);
        verify(bookings).cancelReservation("tenant", "r1", "user");
        assertThat(result.state().cancellationId()).isEmpty();
    }

    @Test
    void missingOrExpiredContextCannotCancelAndKeepClearsState() {
        var invalid = service.handlePostback(tenant, "user", "action=cancel_confirm&reservation_id=r1&confirmation=old", "event", ConversationContext.History.empty());
        assertThat(invalid.messages().getFirst().get("text").toString()).contains("已失效");
        verifyNoInteractions(bookings);
        var kept = service.handlePostback(tenant, "user", "action=keep_booking", "event", ConversationContext.History.empty());
        assertThat(kept.state().cancellationId()).isEmpty();
    }
}
