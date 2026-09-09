package com.thang.chargeops.booking.policy.impl;

import com.thang.chargeops.booking.enums.CancellationRefundTier;
import com.thang.chargeops.booking.exception.BookingCancellationDomainException;
import com.thang.chargeops.booking.exception.violation.BookingCancellationViolation;
import com.thang.chargeops.booking.policy.BookingCancellationPolicy;
import com.thang.chargeops.booking.policy.model.CancellationPolicySummary;
import com.thang.chargeops.booking.policy.model.CancellationRefundContext;
import com.thang.chargeops.booking.policy.model.CancellationRefundDecision;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.booking.config.BookingPolicyConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Voluntary cancellation policy. Station-failure refunds require a separate
 * verified finding/command, never a Driver-controlled flag here.
 * Published defaults are supplied by BookingPolicyConfig (SystemConfig in BKG-002);
 * existing bookings are evaluated only against their own snapshot deadlines.
 */
@Component
@RequiredArgsConstructor
public class PlatformBookingCancellationPolicy implements BookingCancellationPolicy {

    private final BookingPolicyConfig bookingPolicyConfig;


    @Override
    public CancellationRefundDecision calculateRefund(
            CancellationRefundContext booking, Instant cancelledAt, boolean noShow
    ) {
        requireCalculationInputs(booking, cancelledAt);
        Instant deadline = booking.freeCancellationDeadline();
        if (deadline != null && deadline.isAfter(booking.startAt())) {
            deadline = booking.startAt();
        }

        boolean withinGrace = false;
        if (!noShow && booking.status() == BookingStatus.CONFIRMED
                && booking.checkedInAt() == null) {
            if (booking.paymentConfirmedAt() == null) {
                throw violation(BookingCancellationViolation.PAYMENT_CONFIRMATION_REQUIRED,
                        "Confirmed booking requires first server payment confirmation time");
            }
            if (deadline == null) {
                throw violation(BookingCancellationViolation.FREE_CANCELLATION_DEADLINE_REQUIRED,
                        "Confirmed booking requires its snapshot cancellation deadline");
            }
            if (deadline.isBefore(booking.paymentConfirmedAt())) {
                throw violation(BookingCancellationViolation.INVALID_CONFIRMATION_DEADLINE,
                        "Cancellation deadline cannot precede payment confirmation");
            }
            if (cancelledAt.isBefore(booking.paymentConfirmedAt())) {
                throw violation(BookingCancellationViolation.CANCELLATION_BEFORE_CONFIRMATION,
                        "Cancellation time cannot precede payment confirmation");
            }
            withinGrace = cancelledAt.isBefore(deadline);
        }

        BigDecimal refundAmount = withinGrace
                ? booking.refundablePackageAmount() : BigDecimal.ZERO;
        return new CancellationRefundDecision(
                withinGrace ? CancellationRefundTier.GRACE : CancellationRefundTier.NONE,
                withinGrace ? 100 : 0,
                refundAmount,
                booking.refundablePackageAmount().subtract(refundAmount),
                deadline
        );
    }

    @Override
    public CancellationPolicySummary getSummary() {
        return bookingPolicyConfig.getCancellationSummary();
    }

    private void requireCalculationInputs(CancellationRefundContext booking, Instant cancelledAt) {
        if (booking == null) {
            throw violation(BookingCancellationViolation.BOOKING_REQUIRED,
                    "Cancellation refund requires server-side booking facts");
        }
        if (booking.status() == null) {
            throw violation(BookingCancellationViolation.BOOKING_STATUS_REQUIRED,
                    "Cancellation refund requires booking status");
        }
        if (booking.startAt() == null) {
            throw violation(BookingCancellationViolation.BOOKING_START_AT_REQUIRED,
                    "Cancellation refund requires booking start time");
        }
        if (booking.refundablePackageAmount() == null) {
            throw violation(BookingCancellationViolation.BOOKING_AMOUNT_REQUIRED,
                    "Cancellation refund requires remaining refundable package amount");
        }
        if (cancelledAt == null) {
            throw violation(BookingCancellationViolation.CANCELLATION_TIME_REQUIRED,
                    "Cancellation refund requires cancellation time");
        }
        if (booking.refundablePackageAmount().signum() < 0) {
            throw violation(BookingCancellationViolation.BOOKING_AMOUNT_NEGATIVE,
                    "Remaining refundable package amount cannot be negative");
        }
    }

    private BookingCancellationDomainException violation(
            BookingCancellationViolation violation, String message
    ) {
        return new BookingCancellationDomainException(violation, message);
    }
}
