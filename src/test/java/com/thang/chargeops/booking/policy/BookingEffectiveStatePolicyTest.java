package com.thang.chargeops.booking.policy;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.service.model.BookingReadEvaluation;
import com.thang.chargeops.common.enums.BookingStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingEffectiveStatePolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");

    @Mock private Booking booking;
    private final BookingEffectiveStatePolicy policy = new BookingEffectiveStatePolicy();

    @Test
    void pendingExpiresAtInclusiveBoundary() {
        when(booking.getStatus()).thenReturn(BookingStatus.PENDING);
        when(booking.getExpiresAt()).thenReturn(NOW);

        var result = policy.evaluate(booking, NOW);

        assertThat(result.effectiveStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(result.stateReconciliationPending()).isTrue();
    }

    @Test
    void confirmedDeadlineInclusiveBoundarySynthesizesNoShow() {
        when(booking.getStatus()).thenReturn(BookingStatus.CONFIRMED);
        when(booking.getCheckInDeadline()).thenReturn(NOW);

        var result = policy.evaluate(booking, NOW);

        assertThat(result.effectiveStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(result.cancellationReason())
                .isEqualTo(BookingReadEvaluation.CancellationReason.NO_SHOW);
    }

    @Test
    void missingPendingDeadlineFailsClosedWithoutChangingStatus() {
        when(booking.getStatus()).thenReturn(BookingStatus.PENDING);
        when(booking.getExpiresAt()).thenReturn(null);

        var result = policy.evaluate(booking, NOW);

        assertThat(result.effectiveStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(result.stateReconciliationPending()).isTrue();
    }

    @Test
    void terminalStatusIsNeverReclassified() {
        when(booking.getStatus()).thenReturn(BookingStatus.COMPLETED);

        var result = policy.evaluate(booking, NOW);

        assertThat(result.effectiveStatus()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(result.stateReconciliationPending()).isFalse();
    }
}
