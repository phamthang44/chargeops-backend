package com.thang.chargeops.booking.policy;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.service.model.BookingReadEvaluation;
import com.thang.chargeops.common.enums.BookingStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/** Actor-neutral, side-effect-free effective state evaluation for booking reads. */
@Component
public class BookingEffectiveStatePolicy {

    public Evaluation evaluate(Booking booking, Instant evaluatedAt) {
        Objects.requireNonNull(booking, "booking must not be null");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");

        BookingStatus persisted = Objects.requireNonNull(booking.getStatus(), "booking status must not be null");
        BookingStatus effective = persisted;
        BookingReadEvaluation.CancellationReason reason = persistedReason(booking.getCancellationReason());

        if (persisted == BookingStatus.PENDING && booking.getExpiresAt() != null
                && !evaluatedAt.isBefore(booking.getExpiresAt())) {
            effective = BookingStatus.EXPIRED;
        } else if (persisted == BookingStatus.CONFIRMED && booking.getCheckInDeadline() != null
                && !evaluatedAt.isBefore(booking.getCheckInDeadline())) {
            effective = BookingStatus.CANCELLED;
            reason = BookingReadEvaluation.CancellationReason.NO_SHOW;
        }

        boolean missingBoundary = (persisted == BookingStatus.PENDING && booking.getExpiresAt() == null)
                || (persisted == BookingStatus.CONFIRMED && booking.getCheckInDeadline() == null);
        return new Evaluation(effective, effective != persisted || missingBoundary, reason);
    }

    private BookingReadEvaluation.CancellationReason persistedReason(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return BookingReadEvaluation.CancellationReason.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public record Evaluation(
            BookingStatus effectiveStatus,
            boolean stateReconciliationPending,
            BookingReadEvaluation.CancellationReason cancellationReason
    ) {}
}
