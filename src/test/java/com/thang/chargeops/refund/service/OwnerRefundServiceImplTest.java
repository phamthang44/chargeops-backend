package com.thang.chargeops.refund.service;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.refund.executor.RefundExecutorRegistry;
import com.thang.chargeops.refund.projection.RefundExecutionRouteProjection;
import com.thang.chargeops.refund.projection.OwnerRefundTotalsProjection;
import com.thang.chargeops.refund.repository.RefundAttemptRepository;
import com.thang.chargeops.refund.repository.RefundRepository;
import com.thang.chargeops.refund.service.impl.OwnerRefundServiceImpl;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.repository.ConnectorRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OwnerRefundServiceImplTest {
    @Mock CurrentProfileProvider currentProfileProvider;
    @Mock UserProfileRepository userProfileRepository;
    @Mock ConnectorRepository connectorRepository;
    @Mock BookingRepository bookingRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock PaymentTransactionRepository paymentTransactionRepository;
    @Mock RefundRepository refundRepository;
    @Mock RefundAttemptRepository attemptRepository;
    @Mock RefundExecutorRegistry executorRegistry;
    @Mock RefundExecutionResultHandler resultHandler;
    @Mock RefundDetailAssembler assembler;
    @Mock java.time.Clock applicationClock;
    @InjectMocks OwnerRefundServiceImpl service;

    @Test
    void summaryReadsNamedAggregateColumns() {
        UUID ownerId = UUID.randomUUID();
        OwnerRefundTotalsProjection totals = mock(OwnerRefundTotalsProjection.class);
        when(currentProfileProvider.requireProfileId()).thenReturn(ownerId);
        when(refundRepository.summarizeOwnerSimulatorRefunds(ownerId)).thenReturn(totals);
        when(totals.getPendingCount()).thenReturn(2L);
        when(totals.getSucceededCount()).thenReturn(3L);
        when(totals.getNeedsAdminCount()).thenReturn(1L);
        when(totals.getTotalAmount()).thenReturn(BigDecimal.valueOf(5000));
        when(totals.getPendingAmount()).thenReturn(BigDecimal.valueOf(2000));
        when(attemptRepository.countOwnerFailedSimulatorAttempts(ownerId)).thenReturn(4L);

        var summary = service.summary();

        assertEquals(2, summary.totalPendingCount());
        assertEquals(3, summary.totalSucceededCount());
        assertEquals(4, summary.totalFailedAttemptsCount());
        assertEquals(1, summary.requiresOwnerActionCount());
        assertEquals(5000, summary.totalRefundAmountVnd());
        assertEquals(2000, summary.pendingRefundAmountVnd());
    }

    @Test
    void guessedRefundIdFromAnotherOwnerDoesNotRevealDetail() {
        UUID ownerId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        when(currentProfileProvider.requireProfileId()).thenReturn(ownerId);
        Refund refund = mock(Refund.class);
        Booking booking = bookingOwnedBy(UUID.randomUUID());
        when(refund.getBooking()).thenReturn(booking);
        when(refundRepository.findById(refundId)).thenReturn(Optional.of(refund));

        assertThrows(AppException.class, () -> service.get(refundId));
        verifyNoInteractions(attemptRepository, assembler);
    }

    @Test
    void guessedRefundIdFromAnotherOwnerCannotReachPaymentOrSimulator() {
        UUID ownerId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UserProfile actor = mock(UserProfile.class);
        UserProfile lockedOwner = mock(UserProfile.class);
        when(actor.getId()).thenReturn(ownerId);
        when(lockedOwner.getId()).thenReturn(ownerId);
        when(currentProfileProvider.requireProfile()).thenReturn(actor);
        when(userProfileRepository.findByIdWithLock(ownerId)).thenReturn(Optional.of(lockedOwner));
        RefundExecutionRouteProjection route = mock(RefundExecutionRouteProjection.class);
        when(route.getConnectorId()).thenReturn(connectorId);
        when(route.getBookingId()).thenReturn(bookingId);
        when(refundRepository.findExecutionRouteById(refundId)).thenReturn(Optional.of(route));
        when(connectorRepository.findByIdWithLock(connectorId)).thenReturn(Optional.of(mock(Connector.class)));
        Booking foreignBooking = bookingOwnedBy(UUID.randomUUID());
        when(bookingRepository.findByIdWithLock(bookingId)).thenReturn(Optional.of(foreignBooking));

        assertThrows(AppException.class, () -> service.retry(refundId, UUID.randomUUID(), 0));
        verifyNoInteractions(paymentRepository, paymentTransactionRepository, attemptRepository, executorRegistry);
    }

    private Booking bookingOwnedBy(UUID ownerId) {
        Booking booking = mock(Booking.class);
        Connector connector = mock(Connector.class);
        ChargePoint chargePoint = mock(ChargePoint.class);
        Station station = mock(Station.class);
        UserProfile owner = mock(UserProfile.class);
        when(booking.getConnector()).thenReturn(connector);
        when(connector.getChargePoint()).thenReturn(chargePoint);
        when(chargePoint.getStation()).thenReturn(station);
        when(station.getOwner()).thenReturn(owner);
        when(owner.getId()).thenReturn(ownerId);
        return booking;
    }
}
