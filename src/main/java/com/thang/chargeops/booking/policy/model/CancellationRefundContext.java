package com.thang.chargeops.booking.policy.model;

import com.thang.chargeops.common.enums.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Server-side facts for voluntary cancellation. This is not a request DTO.
 * The command service must load these values under the booking/payment locks.
 * freeCancellationDeadline is the deadline snapshotted on first payment
 * confirmation, never recomputed from the current platform configuration.
 * refundablePackageAmount excludes excess receipts and refunds already paid
 * or reserved. Missing confirmation data must never fall back to createdAt.
 */
public record CancellationRefundContext(
        BookingStatus status,
        Instant paymentConfirmedAt,
        Instant startAt,
        Instant checkedInAt,
        Instant freeCancellationDeadline,
        BigDecimal refundablePackageAmount
) {
}
