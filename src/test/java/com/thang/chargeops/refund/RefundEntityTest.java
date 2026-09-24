package com.thang.chargeops.refund;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.common.enums.PaymentApplicationClassification;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.RefundErrorCode;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.entity.PaymentTransaction;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.refund.entity.RefundAttempt;
import com.thang.chargeops.refund.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RefundEntityTest {
    private static final Instant DECIDED_AT = Instant.parse("2026-09-22T08:00:00Z");
    private static final String PAYLOAD_HASH = "a".repeat(64);

    private Booking booking;
    private Payment payment;
    private PaymentTransaction source;
    private UserProfile actor;

    @BeforeEach
    void setUp() {
        booking = mock(Booking.class);
        payment = mock(Payment.class);
        source = mock(PaymentTransaction.class);
        actor = mock(UserProfile.class);

        when(payment.getBooking()).thenReturn(booking);
        when(payment.getAmount()).thenReturn(new BigDecimal("120000.00"));
        when(payment.getCurrency()).thenReturn("VND");
        when(payment.getStatus()).thenReturn(PaymentStatus.PAID);
        when(source.getPayment()).thenReturn(payment);
        when(source.getAmount()).thenReturn(new BigDecimal("120000.00"));
        when(source.getCurrency()).thenReturn("VND");
        when(source.getApplicationClassification()).thenReturn(PaymentApplicationClassification.APPLIED);
    }

    @Test
    void createPending_derivesExactPackageMoneyAndStartsAsObligation() {
        Refund refund = pendingRefund();

        assertThat(refund.getBooking()).isSameAs(booking);
        assertThat(refund.getPayment()).isSameAs(payment);
        assertThat(refund.getSourcePaymentTransaction()).isSameAs(source);
        assertThat(refund.getAmount()).isEqualByComparingTo("120000.00");
        assertThat(refund.getCurrency()).isEqualTo("VND");
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(refund.getSuccessfulAttempt()).isNull();
        assertThat(refund.getCompletedAt()).isNull();
        assertThat(refund.getVersion()).isZero();
    }

    @Test
    void createPending_rejectsUnappliedOrMismatchedReceipt() {
        when(source.getApplicationClassification()).thenReturn(PaymentApplicationClassification.UNAPPLIED);

        assertThatThrownBy(this::pendingRefund)
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(RefundErrorCode.EXECUTION_CONFLICT));

        when(source.getApplicationClassification()).thenReturn(PaymentApplicationClassification.APPLIED);
        when(source.getAmount()).thenReturn(new BigDecimal("119000.00"));

        assertThatThrownBy(this::pendingRefund)
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(RefundErrorCode.AMOUNT_CONFLICT));
    }

    @Test
    void createPending_rejectsFractionalVnd() {
        when(payment.getAmount()).thenReturn(new BigDecimal("120000.50"));
        when(source.getAmount()).thenReturn(new BigDecimal("120000.50"));

        assertThatThrownBy(this::pendingRefund)
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(RefundErrorCode.AMOUNT_CONFLICT));
    }

    @Test
    void failedAttempt_remainsAuditWhileRefundStaysPending() {
        Refund refund = pendingRefund();
        RefundAttempt attempt = startedAttempt(refund, 1);

        attempt.completeFailed("SIMULATED_DECLINE", DECIDED_AT.plusSeconds(2));

        assertThat(attempt.getStatus()).isEqualTo(RefundAttemptStatus.FAILED);
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING);
        assertThat(refund.getSuccessfulAttempt()).isNull();
        assertThatThrownBy(() -> refund.completeWith(attempt, DECIDED_AT.plusSeconds(2)))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(RefundErrorCode.EXECUTION_CONFLICT));
    }

    @Test
    void successfulAttempt_completesRefundExactlyOnce() {
        Refund refund = pendingRefund();
        RefundAttempt attempt = startedAttempt(refund, 1);
        Instant completedAt = DECIDED_AT.plusSeconds(2);

        attempt.completeSucceeded("SIM-REF-1", "SIM-TRANSFER-1", completedAt);
        refund.completeWith(attempt, completedAt);
        refund.completeWith(attempt, completedAt);

        assertThat(attempt.isSucceeded()).isTrue();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(refund.getSuccessfulAttempt()).isSameAs(attempt);
        assertThat(refund.getCompletedAt()).isEqualTo(completedAt);
    }

    @Test
    void attempt_requiresPositiveSequenceAndCanonicalHash() {
        Refund refund = pendingRefund();
        PendingRefundAttemptSpec invalid = new PendingRefundAttemptSpec(
                refund, 0, RefundExecutionMode.SIMULATOR, UUID.randomUUID(),
                "ABC", "refund:key", actor, DECIDED_AT.plusSeconds(1)
        );

        assertThatThrownBy(() -> RefundAttempt.start(invalid))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(RefundErrorCode.EXECUTION_CONFLICT));
    }

    private Refund pendingRefund() {
        return Refund.createPending(new PendingRefundSpec(
                booking,
                payment,
                source,
                RefundReason.VOLUNTARY_GRACE,
                RefundBasisType.BOOKING_CANCELLATION,
                UUID.randomUUID(),
                DECIDED_AT,
                actor
        ));
    }

    private RefundAttempt startedAttempt(Refund refund, int sequence) {
        return RefundAttempt.start(new PendingRefundAttemptSpec(
                refund,
                sequence,
                RefundExecutionMode.SIMULATOR,
                UUID.randomUUID(),
                PAYLOAD_HASH,
                "refund:" + UUID.randomUUID(),
                actor,
                DECIDED_AT.plusSeconds(1)
        ));
    }
}
