package com.thang.chargeops.payment.service;

import com.thang.chargeops.common.enums.PaymentApplicationClassification;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.PaymentErrorCode;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.entity.PaymentTransaction;
import com.thang.chargeops.payment.projection.OwnerFinanceTotalsProjection;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.payment.service.impl.OwnerFinanceServiceImpl;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.refund.model.RefundStatus;
import com.thang.chargeops.refund.repository.RefundRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OwnerFinanceServiceImplTest {
    @Mock CurrentProfileProvider currentProfileProvider;
    @Mock PaymentRepository paymentRepository;
    @Mock PaymentTransactionRepository transactionRepository;
    @Mock RefundRepository refundRepository;
    @InjectMocks OwnerFinanceServiceImpl service;

    @Test
    void summaryComputesCorrectlyWhenConsistent() {
        UUID ownerId = UUID.randomUUID();
        OwnerFinanceTotalsProjection totals = mock(OwnerFinanceTotalsProjection.class);
        when(currentProfileProvider.requireProfileId()).thenReturn(ownerId);
        when(paymentRepository.countOwnerSimulatorDriftedPayments(ownerId)).thenReturn(0L);
        when(paymentRepository.summarizeOwnerSimulatorLedger(ownerId)).thenReturn(totals);
        when(transactionRepository.sumOwnerAppliedSimulatorReceipts(ownerId)).thenReturn(BigDecimal.valueOf(10000));
        when(refundRepository.sumOwnerSucceededSimulatorRefunds(ownerId)).thenReturn(BigDecimal.valueOf(3000));
        when(refundRepository.sumOwnerPendingSimulatorRefunds(ownerId)).thenReturn(BigDecimal.valueOf(1000));
        when(totals.getPaymentCount()).thenReturn(4L);
        when(totals.getPaidCount()).thenReturn(2L);

        var summary = service.summary();

        assertEquals(10000, summary.grossVnd());
        assertEquals(3000, summary.refundedVnd());
        assertEquals(7000, summary.netVnd());
        assertEquals(1000, summary.pendingRefundVnd());
        assertEquals(4, summary.totalBookings());
        assertEquals(2, summary.paidBookings());
    }

    @Test
    void summaryThrowsExceptionOnItemLevelDrift() {
        UUID ownerId = UUID.randomUUID();
        when(currentProfileProvider.requireProfileId()).thenReturn(ownerId);
        when(paymentRepository.countOwnerSimulatorDriftedPayments(ownerId)).thenReturn(1L);

        assertThatThrownBy(() -> service.summary())
                .isInstanceOfSatisfying(AppException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(PaymentErrorCode.PROJECTION_BOUNDS_VIOLATED);
                });
    }

    @Test
    void detailThrowsExceptionWhenReceiptsDriftFromMirror() {
        UUID ownerId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Payment payment = mock(Payment.class, RETURNS_DEEP_STUBS);
        when(currentProfileProvider.requireProfileId()).thenReturn(ownerId);
        when(paymentRepository.findOwnerSimulatorBookingPayment(ownerId, bookingId)).thenReturn(Optional.of(payment));
        when(refundRepository.findByBookingIdOrderByCreatedAtAscIdAsc(bookingId)).thenReturn(List.of());
        when(payment.getAmount()).thenReturn(BigDecimal.valueOf(100000));
        when(payment.getRefundAmount()).thenReturn(BigDecimal.ZERO);
        when(payment.getPaidAt()).thenReturn(Instant.now());

        // Receipt only sums to 80000 instead of 100000
        PaymentTransaction tx = mock(PaymentTransaction.class);
        when(tx.getId()).thenReturn(UUID.randomUUID());
        when(tx.getTransactionRef()).thenReturn("REF1");
        when(tx.getAmount()).thenReturn(BigDecimal.valueOf(80000));
        when(tx.getReceivedAt()).thenReturn(Instant.now());
        when(tx.getApplicationClassification()).thenReturn(PaymentApplicationClassification.APPLIED);
        when(transactionRepository.findByPaymentIdOrderByReceivedAtAscIdAsc(payment.getId())).thenReturn(List.of(tx));

        assertThatThrownBy(() -> service.get(bookingId))
                .isInstanceOfSatisfying(AppException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(PaymentErrorCode.PROJECTION_BOUNDS_VIOLATED);
                });
    }

    @Test
    void detailThrowsExceptionWhenRefundDriftsFromMirrorOrNullMirror() {
        UUID ownerId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Payment payment = mock(Payment.class, RETURNS_DEEP_STUBS);
        when(currentProfileProvider.requireProfileId()).thenReturn(ownerId);
        when(paymentRepository.findOwnerSimulatorBookingPayment(ownerId, bookingId)).thenReturn(Optional.of(payment));

        Refund refund = mock(Refund.class);
        when(refund.getStatus()).thenReturn(RefundStatus.SUCCEEDED);
        when(refund.getAmount()).thenReturn(BigDecimal.valueOf(50000));
        when(refundRepository.findByBookingIdOrderByCreatedAtAscIdAsc(bookingId)).thenReturn(List.of(refund));

        when(payment.getAmount()).thenReturn(BigDecimal.valueOf(100000));
        // Mirror refundAmount is NULL even though refund is SUCCEEDED (50000)
        when(payment.getRefundAmount()).thenReturn(null);
        when(payment.getPaidAt()).thenReturn(Instant.now());

        PaymentTransaction tx = mock(PaymentTransaction.class);
        when(tx.getId()).thenReturn(UUID.randomUUID());
        when(tx.getTransactionRef()).thenReturn("REF1");
        when(tx.getAmount()).thenReturn(BigDecimal.valueOf(100000));
        when(tx.getReceivedAt()).thenReturn(Instant.now());
        when(tx.getApplicationClassification()).thenReturn(PaymentApplicationClassification.APPLIED);
        when(transactionRepository.findByPaymentIdOrderByReceivedAtAscIdAsc(payment.getId())).thenReturn(List.of(tx));

        assertThatThrownBy(() -> service.get(bookingId))
                .isInstanceOfSatisfying(AppException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(PaymentErrorCode.PROJECTION_BOUNDS_VIOLATED);
                });
    }

    @Test
    void detailThrowsExceptionWhenPaidAtNull() {
        UUID ownerId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Payment payment = mock(Payment.class, RETURNS_DEEP_STUBS);
        when(currentProfileProvider.requireProfileId()).thenReturn(ownerId);
        when(paymentRepository.findOwnerSimulatorBookingPayment(ownerId, bookingId)).thenReturn(Optional.of(payment));
        when(refundRepository.findByBookingIdOrderByCreatedAtAscIdAsc(bookingId)).thenReturn(List.of());

        when(payment.getAmount()).thenReturn(BigDecimal.valueOf(100000));
        when(payment.getRefundAmount()).thenReturn(BigDecimal.ZERO);
        // paidAt is null on settled payment
        when(payment.getPaidAt()).thenReturn(null);

        PaymentTransaction tx = mock(PaymentTransaction.class);
        when(tx.getId()).thenReturn(UUID.randomUUID());
        when(tx.getTransactionRef()).thenReturn("REF1");
        when(tx.getAmount()).thenReturn(BigDecimal.valueOf(100000));
        when(tx.getReceivedAt()).thenReturn(Instant.now());
        when(tx.getApplicationClassification()).thenReturn(PaymentApplicationClassification.APPLIED);
        when(transactionRepository.findByPaymentIdOrderByReceivedAtAscIdAsc(payment.getId())).thenReturn(List.of(tx));

        assertThatThrownBy(() -> service.get(bookingId))
                .isInstanceOfSatisfying(AppException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(PaymentErrorCode.PROJECTION_BOUNDS_VIOLATED);
                });
    }
}
