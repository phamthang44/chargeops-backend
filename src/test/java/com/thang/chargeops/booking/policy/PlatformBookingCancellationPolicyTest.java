package com.thang.chargeops.booking.policy;

import com.thang.chargeops.booking.config.BookingPolicyConfig;
import com.thang.chargeops.booking.enums.CancellationRefundTier;
import com.thang.chargeops.booking.exception.BookingCancellationDomainException;
import com.thang.chargeops.booking.exception.violation.BookingCancellationViolation;
import com.thang.chargeops.booking.policy.impl.PlatformBookingCancellationPolicy;
import com.thang.chargeops.booking.policy.model.CancellationRefundContext;
import com.thang.chargeops.common.enums.BookingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformBookingCancellationPolicyTest {
    private static final Instant PAID_AT = Instant.parse("2026-09-08T03:00:00Z");
    private static final Instant START_AT = Instant.parse("2026-09-09T03:00:00Z");
    private static final Instant DEADLINE = PAID_AT.plusSeconds(600);
    private PlatformBookingCancellationPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new PlatformBookingCancellationPolicy(BookingPolicyConfig.defaults());
    }

    @Test
    void refundsRemainingPackageUntilOneNanosecondBeforePaymentGraceDeadline() {
        var result = policy.calculateRefund(confirmed(DEADLINE, START_AT), DEADLINE.minusNanos(1), false);
        assertThat(result.tier()).isEqualTo(CancellationRefundTier.GRACE);
        assertThat(result.refundPercent()).isEqualTo(100);
        assertThat(result.refundAmount()).isEqualByComparingTo("79000");
        assertThat(result.cancellationFee()).isZero();
        assertThat(result.graceEndsAt()).isEqualTo(DEADLINE);
    }

    @ParameterizedTest
    @ValueSource(longs = {600, 601, 3600, 82800, 85500})
    void neverRefundsAfterGraceEvenWhenBookingIsFarInTheFuture(long secondsAfterPayment) {
        var result = policy.calculateRefund(confirmed(DEADLINE, START_AT), PAID_AT.plusSeconds(secondsAfterPayment), false);
        assertThat(result.tier()).isEqualTo(CancellationRefundTier.NONE);
        assertThat(result.refundPercent()).isZero();
        assertThat(result.refundAmount()).isZero();
        assertThat(result.cancellationFee()).isEqualByComparingTo("79000");
    }

    @Test
    void startTimeClosesGraceEvenIfStoredDeadlineIsLater() {
        Instant earlyStart = PAID_AT.plusSeconds(300);
        var context = confirmed(DEADLINE, earlyStart);
        assertThat(policy.calculateRefund(context, earlyStart.minusNanos(1), false).refundPercent()).isEqualTo(100);
        var atStart = policy.calculateRefund(context, earlyStart, false);
        assertThat(atStart.refundAmount()).isZero();
        assertThat(atStart.graceEndsAt()).isEqualTo(earlyStart);
    }

    @Test
    void honorsStoredDeadlineInsteadOfApplyingCurrentTenMinuteDefaultAgain() {
        Instant storedDeadline = PAID_AT.plusSeconds(300);
        assertThat(policy.getSummary().gracePeriodMinutes()).isEqualTo(10);
        var result = policy.calculateRefund(confirmed(storedDeadline, START_AT), storedDeadline, false);
        assertThat(result.refundAmount()).isZero();
        assertThat(result.graceEndsAt()).isEqualTo(storedDeadline);
    }

    @Test
    void checkedInMarkerPreventsVoluntaryRefundEvenIfStatusIsStillConfirmed() {
        var context = new CancellationRefundContext(BookingStatus.CONFIRMED, PAID_AT, START_AT,
                PAID_AT.plusSeconds(60), DEADLINE, new BigDecimal("79000"));
        assertThat(policy.calculateRefund(context, PAID_AT.plusSeconds(120), false).refundAmount()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = "CONFIRMED", mode = EnumSource.Mode.EXCLUDE)
    void nonConfirmedBookingsNeverGrantVoluntaryRefund(BookingStatus status) {
        var context = new CancellationRefundContext(status, null, START_AT, null, null, BigDecimal.ZERO);
        var result = policy.calculateRefund(context, PAID_AT, false);
        assertThat(result.refundAmount()).isZero();
        assertThat(result.graceEndsAt()).isNull();
    }

    @Test
    void noShowDoesNotGrantVoluntaryRefundWithinGrace() {
        assertThat(policy.calculateRefund(confirmed(DEADLINE, START_AT), PAID_AT.plusSeconds(1), true)
                .refundAmount()).isZero();
    }

    @Test
    void zeroRemainingBalanceCannotCreateMoneyEvenWithinGrace() {
        var context = new CancellationRefundContext(BookingStatus.CONFIRMED, PAID_AT, START_AT,
                null, DEADLINE, BigDecimal.ZERO);
        assertThat(policy.calculateRefund(context, PAID_AT, false).refundAmount()).isZero();
    }

    @Test
    void confirmedLegacyBookingWithoutPaymentTimeCannotFallBackToCreationTime() {
        var context = new CancellationRefundContext(BookingStatus.CONFIRMED, null, START_AT,
                null, DEADLINE, BigDecimal.ONE);
        assertViolation(context, PAID_AT, BookingCancellationViolation.PAYMENT_CONFIRMATION_REQUIRED);
    }

    @Test
    void missingStoredDeadlineIsNotRebuiltFromCurrentConfiguration() {
        assertViolation(confirmed(null, START_AT), PAID_AT,
                BookingCancellationViolation.FREE_CANCELLATION_DEADLINE_REQUIRED);
    }

    @Test
    void rejectsImpossibleConfirmationTimeline() {
        assertViolation(confirmed(PAID_AT.minusSeconds(1), START_AT), PAID_AT,
                BookingCancellationViolation.INVALID_CONFIRMATION_DEADLINE);
        assertViolation(confirmed(DEADLINE, START_AT), PAID_AT.minusNanos(1),
                BookingCancellationViolation.CANCELLATION_BEFORE_CONFIRMATION);
    }

    @Test
    void validatesMissingInputsAndNegativeBalanceWithDomainViolations() {
        assertViolation(null, PAID_AT, BookingCancellationViolation.BOOKING_REQUIRED);
        assertViolation(confirmed(DEADLINE, START_AT), null, BookingCancellationViolation.CANCELLATION_TIME_REQUIRED);
        assertViolation(new CancellationRefundContext(null, PAID_AT, START_AT, null, DEADLINE, BigDecimal.ONE),
                PAID_AT, BookingCancellationViolation.BOOKING_STATUS_REQUIRED);
        assertViolation(confirmed(DEADLINE, null), PAID_AT, BookingCancellationViolation.BOOKING_START_AT_REQUIRED);
        assertViolation(new CancellationRefundContext(BookingStatus.CONFIRMED, PAID_AT, START_AT, null, DEADLINE, null),
                PAID_AT, BookingCancellationViolation.BOOKING_AMOUNT_REQUIRED);
        assertViolation(new CancellationRefundContext(BookingStatus.CONFIRMED, PAID_AT, START_AT, null, DEADLINE, BigDecimal.ONE.negate()),
                PAID_AT, BookingCancellationViolation.BOOKING_AMOUNT_NEGATIVE);
    }

    @Test
    void publishesPaymentBasedGraceAndSeparatelyVerifiedStationFailure() {
        var summary = policy.getSummary();
        assertThat(summary.policyVersion()).isEqualTo("booking-v4.9");
        assertThat(summary.gracePeriodMinutes()).isEqualTo(10);
        assertThat(summary.graceStartsAt()).isEqualTo("PAYMENT_CONFIRMED_AT");
        assertThat(summary.requiresBeforeBookingStart()).isTrue();
        assertThat(summary.requiresNotCheckedIn()).isTrue();
        assertThat(summary.withinGraceRefundPercent()).isEqualTo(100);
        assertThat(summary.afterGraceRefundPercent()).isZero();
        assertThat(summary.noShowRefundPercent()).isZero();
        assertThat(summary.verifiedStationFailureRefundPercent()).isEqualTo(100);
        assertThat(summary.stationFailureRequiresVerification()).isTrue();
    }

    @Test
    void configChangeUpdatesSummaryWithoutAffectingExistingBookingSnapshotDeadline() {
        var context = confirmed(DEADLINE, START_AT);

        BookingPolicyConfig updatedConfig = org.mockito.Mockito.spy(BookingPolicyConfig.defaults());
        org.mockito.Mockito.doReturn(5).when(updatedConfig).getCancellationGraceMinutes();
        PlatformBookingCancellationPolicy updatedPolicy = new PlatformBookingCancellationPolicy(updatedConfig);

        // Discovery summary reflects the new 5-minute grace policy
        assertThat(updatedPolicy.getSummary().gracePeriodMinutes()).isEqualTo(5);

        // Existing booking still gets 100% refund at 6 minutes because it is evaluated against stored deadline
        Instant cancelAtSixMinutes = PAID_AT.plusSeconds(360);
        var decision = updatedPolicy.calculateRefund(context, cancelAtSixMinutes, false);
        assertThat(decision.tier()).isEqualTo(CancellationRefundTier.GRACE);
        assertThat(decision.refundPercent()).isEqualTo(100);
        assertThat(decision.graceEndsAt()).isEqualTo(DEADLINE);
    }

    private CancellationRefundContext confirmed(Instant deadline, Instant startAt) {
        return new CancellationRefundContext(BookingStatus.CONFIRMED, PAID_AT, startAt,
                null, deadline, new BigDecimal("79000"));
    }

    private void assertViolation(CancellationRefundContext context, Instant at, BookingCancellationViolation expected) {
        assertThatThrownBy(() -> policy.calculateRefund(context, at, false))
                .isInstanceOfSatisfying(BookingCancellationDomainException.class,
                        exception -> assertThat(exception.getViolation()).isEqualTo(expected));
    }
}
