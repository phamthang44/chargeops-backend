package com.thang.chargeops.booking.policy.impl;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.enums.CancellationRefundTier;
import com.thang.chargeops.booking.exception.BookingCancellationDomainException;
import com.thang.chargeops.booking.exception.violation.BookingCancellationViolation;
import com.thang.chargeops.booking.policy.BookingCancellationPolicy;
import com.thang.chargeops.booking.policy.model.CancellationPolicySummary;
import com.thang.chargeops.booking.policy.model.CancellationRefundDecision;
import com.thang.chargeops.booking.policy.model.CancellationRefundRule;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Chính sách hủy mặc định cố định của toàn platform theo FR05/FR08.
 * Đây không phải cấu hình của Station Owner và cố ý không đọc database.
 */
@Component
public class PlatformBookingCancellationPolicy implements BookingCancellationPolicy {

    private static final int GRACE_PERIOD_MINUTES = 5;
    private static final int FULL_REFUND_MINUTES = 60;
    private static final int PARTIAL_REFUND_MINUTES = 15;
    private static final int FULL_REFUND_PERCENT = 100;
    private static final int PARTIAL_REFUND_PERCENT = 50;
    private static final int NO_REFUND_PERCENT = 0;

    private static final CancellationPolicySummary SUMMARY = new CancellationPolicySummary(
            GRACE_PERIOD_MINUTES,
            List.of(
                    new CancellationRefundRule(
                            CancellationRefundTier.FULL,
                            FULL_REFUND_PERCENT,
                            FULL_REFUND_MINUTES,
                            null,
                            false
                    ),
                    new CancellationRefundRule(
                            CancellationRefundTier.PARTIAL,
                            PARTIAL_REFUND_PERCENT,
                            PARTIAL_REFUND_MINUTES,
                            FULL_REFUND_MINUTES,
                            false
                    ),
                    new CancellationRefundRule(
                            CancellationRefundTier.NONE,
                            NO_REFUND_PERCENT,
                            null,
                            PARTIAL_REFUND_MINUTES,
                            true
                    )
            )
    );

    @Override
    public CancellationRefundDecision calculateRefund(
            Booking booking,
            Instant cancelledAt,
            boolean noShow
    ) {
        requireCalculationInputs(booking, cancelledAt);

        Instant graceEndsAt = booking.getCreatedAt()
                .plus(Duration.ofMinutes(GRACE_PERIOD_MINUTES));
        CancellationRefundTier tier;
        int refundPercent;

        if (noShow) {
            tier = CancellationRefundTier.NONE;
            refundPercent = NO_REFUND_PERCENT;
        } else if (cancelledAt.isBefore(graceEndsAt)) {
            tier = CancellationRefundTier.GRACE;
            refundPercent = FULL_REFUND_PERCENT;
        } else {
            long minutesBeforeStart = Duration.between(
                    cancelledAt,
                    booking.getStartAt()
            ).toMinutes();
            if (minutesBeforeStart >= FULL_REFUND_MINUTES) {
                tier = CancellationRefundTier.FULL;
                refundPercent = FULL_REFUND_PERCENT;
            } else if (minutesBeforeStart >= PARTIAL_REFUND_MINUTES) {
                tier = CancellationRefundTier.PARTIAL;
                refundPercent = PARTIAL_REFUND_PERCENT;
            } else {
                tier = CancellationRefundTier.NONE;
                refundPercent = NO_REFUND_PERCENT;
            }
        }

        BigDecimal refundAmount = percentageOf(
                booking.getTotalAmount(),
                refundPercent
        );
        return new CancellationRefundDecision(
                tier,
                refundPercent,
                refundAmount,
                booking.getTotalAmount().subtract(refundAmount),
                graceEndsAt
        );
    }

    @Override
    public CancellationPolicySummary getSummary() {
        return SUMMARY;
    }

    private void requireCalculationInputs(Booking booking, Instant cancelledAt) {
        if (booking == null) {
            throw violation(
                    BookingCancellationViolation.BOOKING_REQUIRED,
                    "Cancellation refund requires a booking"
            );
        }
        if (booking.getCreatedAt() == null) {
            throw violation(
                    BookingCancellationViolation.BOOKING_CREATED_AT_REQUIRED,
                    "Cancellation refund requires booking creation time"
            );
        }
        if (booking.getStartAt() == null) {
            throw violation(
                    BookingCancellationViolation.BOOKING_START_AT_REQUIRED,
                    "Cancellation refund requires booking start time"
            );
        }
        if (booking.getTotalAmount() == null) {
            throw violation(
                    BookingCancellationViolation.BOOKING_AMOUNT_REQUIRED,
                    "Cancellation refund requires booking total amount"
            );
        }
        if (cancelledAt == null) {
            throw violation(
                    BookingCancellationViolation.CANCELLATION_TIME_REQUIRED,
                    "Cancellation refund requires cancellation time"
            );
        }
        if (booking.getTotalAmount().signum() < 0) {
            throw violation(
                    BookingCancellationViolation.BOOKING_AMOUNT_NEGATIVE,
                    "Cancellation refund amount cannot be negative"
            );
        }
    }

    private BookingCancellationDomainException violation(
            BookingCancellationViolation violation,
            String message
    ) {
        return new BookingCancellationDomainException(violation, message);
    }

    private BigDecimal percentageOf(BigDecimal amount, int percent) {
        return amount
                .multiply(BigDecimal.valueOf(percent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}
