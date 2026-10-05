package com.thang.chargeops.refund;

import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.common.enums.PaymentEnvironment;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.refund.entity.RefundAutoDispatch;
import com.thang.chargeops.refund.executor.RefundExecutorRegistry;
import com.thang.chargeops.refund.model.RefundAutoDispatchStatus;
import com.thang.chargeops.refund.projection.RefundExecutionRouteProjection;
import com.thang.chargeops.refund.repository.RefundAttemptRepository;
import com.thang.chargeops.refund.repository.RefundAutoDispatchRepository;
import com.thang.chargeops.refund.repository.RefundRepository;
import com.thang.chargeops.refund.service.RefundExecutionResultHandler;
import com.thang.chargeops.refund.service.impl.AutomaticRefundExecutionServiceImpl;
import com.thang.chargeops.station.repository.ConnectorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AutomaticRefundEnvironmentGuardTest {
    @Mock ConnectorRepository connectors;
    @Mock BookingRepository bookings;
    @Mock PaymentRepository payments;
    @Mock PaymentTransactionRepository transactions;
    @Mock RefundRepository refunds;
    @Mock RefundAttemptRepository attempts;
    @Mock RefundAutoDispatchRepository dispatches;
    @Mock RefundExecutorRegistry executors;
    @Mock RefundExecutionResultHandler results;
    @Mock Clock clock;
    @InjectMocks AutomaticRefundExecutionServiceImpl service;

    @Test
    void historicalLivePaymentNeverRunsSimulatorRefund() {
        UUID refundId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID receiptId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-02T10:00:00Z");
        var route = mock(RefundExecutionRouteProjection.class);
        when(route.getConnectorId()).thenReturn(connectorId);
        when(route.getBookingId()).thenReturn(bookingId);
        when(route.getPaymentId()).thenReturn(paymentId);
        when(route.getSourcePaymentTransactionId()).thenReturn(receiptId);
        when(refunds.findExecutionRouteById(refundId)).thenReturn(Optional.of(route));
        when(connectors.findByIdWithLock(connectorId)).thenReturn(Optional.of(mock(com.thang.chargeops.station.entity.Connector.class)));
        when(bookings.findByIdWithLock(bookingId)).thenReturn(Optional.of(mock(com.thang.chargeops.booking.entity.Booking.class)));
        var payment = mock(Payment.class);
        when(payment.getEnvironment()).thenReturn(PaymentEnvironment.LIVE);
        when(payments.findByIdWithLock(paymentId)).thenReturn(Optional.of(payment));
        when(transactions.findByIdWithLock(receiptId)).thenReturn(Optional.of(mock(com.thang.chargeops.payment.entity.PaymentTransaction.class)));
        var dispatch = mock(RefundAutoDispatch.class);
        when(dispatch.getStatus()).thenReturn(RefundAutoDispatchStatus.PENDING);
        when(dispatches.findByRefundIdWithLock(refundId)).thenReturn(Optional.of(dispatch));
        var refund = mock(Refund.class);
        when(refunds.findByIdWithLock(refundId)).thenReturn(Optional.of(refund));
        when(clock.instant()).thenReturn(now);

        assertThat(service.processFirstAttempt(refundId)).isFalse();
        verify(dispatch).markProcessed(now);
        verifyNoInteractions(executors, results, attempts);
    }
}
