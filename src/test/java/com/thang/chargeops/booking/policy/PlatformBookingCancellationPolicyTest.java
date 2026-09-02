package com.thang.chargeops.booking.policy;

import com.thang.chargeops.booking.Booking;
import com.thang.chargeops.booking.enums.CancellationRefundTier;
import com.thang.chargeops.booking.exception.BookingCancellationDomainException;
import com.thang.chargeops.booking.exception.violation.BookingCancellationViolation;
import com.thang.chargeops.booking.policy.impl.PlatformBookingCancellationPolicy;
import com.thang.chargeops.booking.policy.model.CancellationPolicySummary;
import com.thang.chargeops.booking.policy.model.CancellationRefundDecision;
import com.thang.chargeops.booking.policy.model.CancellationRefundRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformBookingCancellationPolicyTest {

    private PlatformBookingCancellationPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new PlatformBookingCancellationPolicy();
    }

    @Test
    void gracePeriodOverridesHowCloseTheBookingStartIs() {
        Booking booking = booking(
                "2026-09-01T03:00:00Z",
                "2026-09-01T03:06:00Z"
        );

        CancellationRefundDecision result = policy.calculateRefund(
                booking,
                Instant.parse("2026-09-01T03:04:59Z"),
                false
        );

        assertThat(result.tier()).isEqualTo(CancellationRefundTier.GRACE);
        assertThat(result.refundPercent()).isEqualTo(100);
        assertThat(result.refundAmount()).isEqualByComparingTo("100000.00");
        assertThat(result.cancellationFee()).isEqualByComparingTo("0.00");
    }

    @Test
    void appliesFullRefundAtExactlySixtyMinutesBeforeStart() {
        Booking booking = booking(
                "2026-09-01T01:00:00Z",
                "2026-09-01T05:00:00Z"
        );

        CancellationRefundDecision result = policy.calculateRefund(
                booking,
                Instant.parse("2026-09-01T04:00:00Z"),
                false
        );

        assertThat(result.tier()).isEqualTo(CancellationRefundTier.FULL);
        assertThat(result.refundPercent()).isEqualTo(100);
    }

    @Test
    void appliesPartialRefundFromFifteenUntilBeforeSixtyMinutes() {
        Booking booking = booking(
                "2026-09-01T01:00:00Z",
                "2026-09-01T05:00:00Z"
        );

        CancellationRefundDecision result = policy.calculateRefund(
                booking,
                Instant.parse("2026-09-01T04:45:00Z"),
                false
        );

        assertThat(result.tier()).isEqualTo(CancellationRefundTier.PARTIAL);
        assertThat(result.refundPercent()).isEqualTo(50);
        assertThat(result.refundAmount()).isEqualByComparingTo("50000.00");
        assertThat(result.cancellationFee()).isEqualByComparingTo("50000.00");
    }

    @Test
    void appliesNoRefundInsideFifteenMinutesAndForNoShow() {
        Booking booking = booking(
                "2026-09-01T01:00:00Z",
                "2026-09-01T05:00:00Z"
        );

        CancellationRefundDecision lateCancellation =
                policy.calculateRefund(
                        booking,
                        Instant.parse("2026-09-01T04:46:00Z"),
                        false
                );
        CancellationRefundDecision noShow =
                policy.calculateRefund(
                        booking,
                        Instant.parse("2026-09-01T05:15:00Z"),
                        true
                );

        assertThat(lateCancellation.tier())
                .isEqualTo(CancellationRefundTier.NONE);
        assertThat(lateCancellation.refundAmount()).isEqualByComparingTo("0.00");
        assertThat(noShow.tier()).isEqualTo(CancellationRefundTier.NONE);
        assertThat(noShow.refundPercent()).isZero();
    }

    @Test
    void exposesOneImmutablePlatformSummaryInsteadOfOwnerConfiguration() {
        CancellationPolicySummary summary = policy.getSummary();

        assertThat(summary.gracePeriodMinutes()).isEqualTo(5);
        assertThat(summary.refundRules())
                .extracting(CancellationRefundRule::refundPercent)
                .containsExactly(100, 50, 0);
        assertThat(summary.refundRules().getLast().appliesToNoShow()).isTrue();
    }

    @Test
    void reportsAStableDomainViolationWhenRequiredBookingDataIsMissing() {
        assertThatThrownBy(() -> policy.calculateRefund(
                null,
                Instant.parse("2026-09-01T04:00:00Z"),
                false
        )).isInstanceOfSatisfying(
                BookingCancellationDomainException.class,
                exception -> assertThat(exception.getViolation())
                        .isEqualTo(BookingCancellationViolation.BOOKING_REQUIRED)
        );
    }

    @Test
    void reportsAStableDomainViolationForNegativeBookingAmount() {
        Booking booking = booking(
                "2026-09-01T01:00:00Z",
                "2026-09-01T05:00:00Z"
        );
        booking.setTotalAmount(new BigDecimal("-1.00"));

        assertThatThrownBy(() -> policy.calculateRefund(
                booking,
                Instant.parse("2026-09-01T04:00:00Z"),
                false
        )).isInstanceOfSatisfying(
                BookingCancellationDomainException.class,
                exception -> assertThat(exception.getViolation())
                        .isEqualTo(BookingCancellationViolation.BOOKING_AMOUNT_NEGATIVE)
        );
    }

    private Booking booking(String createdAt, String startAt) {
        Booking booking = Booking.builder()
                .startAt(Instant.parse(startAt))
                .totalAmount(new BigDecimal("100000.00"))
                .build();
        booking.setCreatedAt(Instant.parse(createdAt));
        return booking;
    }
}
