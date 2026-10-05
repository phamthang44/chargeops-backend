package com.thang.chargeops.booking.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Transaction-bound lifecycle signal. Consumers such as BKG-066 email must use
 * TransactionPhase.AFTER_COMMIT so notification failure cannot roll back Booking.
 */
public record BookingLifecycleEvent(
        UUID bookingId,
        BookingLifecycleEventType type,
        Instant occurredAt
) {
}
