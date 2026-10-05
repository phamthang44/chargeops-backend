package com.thang.chargeops.refund.service;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.RefundErrorCode;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.entity.PaymentTransaction;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.refund.dto.request.ExecuteRefundRequest;
import com.thang.chargeops.refund.dto.request.RefundExecutionOutcome;
import com.thang.chargeops.refund.dto.response.RefundDetailResponse;
import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.refund.entity.RefundAttempt;
import com.thang.chargeops.refund.executor.RefundExecutionResult;
import com.thang.chargeops.refund.executor.RefundExecutor;
import com.thang.chargeops.refund.executor.RefundExecutorRegistry;
import com.thang.chargeops.refund.model.RefundExecutionMode;
import com.thang.chargeops.refund.model.RefundStatus;
import com.thang.chargeops.refund.projection.RefundExecutionRouteProjection;
import com.thang.chargeops.refund.repository.RefundAttemptRepository;
import com.thang.chargeops.refund.repository.RefundRepository;
import com.thang.chargeops.refund.service.impl.AdminRefundServiceImpl;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.repository.ConnectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminRefundServiceImplTest {
    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    @Mock CurrentProfileProvider currentProfileProvider;
    @Mock UserProfileRepository userProfileRepository;
    @Mock ConnectorRepository connectorRepository;
    @Mock BookingRepository bookingRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock PaymentTransactionRepository paymentTransactionRepository;
    @Mock RefundRepository refundRepository;
    @Mock RefundAttemptRepository refundAttemptRepository;
    @Mock RefundExecutorRegistry executorRegistry;
    @Mock RefundExecutor executor;
    @Mock RefundDetailAssembler assembler;
    @Mock RefundExecutionRouteProjection route;
    @Mock Refund refund;
    @Mock Payment payment;
    @Mock Booking booking;
    @Mock PaymentTransaction source;
    @Mock Connector connector;
    @Mock UserProfile actor;
    @Mock RefundDetailResponse response;

    private AdminRefundServiceImpl service;
    private UUID refundId;
    private UUID requestKey;

    @BeforeEach
    void setUp() {
        refundId = UUID.randomUUID();
        requestKey = UUID.randomUUID();
        service = new AdminRefundServiceImpl(
                currentProfileProvider, userProfileRepository, connectorRepository,
                bookingRepository, paymentRepository, paymentTransactionRepository,
                refundRepository, refundAttemptRepository, executorRegistry, assembler,
                new RefundExecutionResultHandler(refundAttemptRepository, refundRepository, paymentRepository),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void succeededAttemptCompletesRefundAndPaymentExactlyOnce() {
        arrangeLockedAggregate();
        ExecuteRefundRequest request = request(RefundExecutionOutcome.SUCCEEDED);
        when(executor.execute(any())).thenReturn(new RefundExecutionResult(
                RefundExecutionOutcome.SUCCEEDED,
                "SIMULATOR",
                "SIM-REF-ABC",
                null,
                NOW,
                "Succeeded"
        ));

        service.execute(refundId, requestKey, request);

        verify(payment).recordFullRefund(new BigDecimal("120000.00"));
        verify(refund).completeWith(any(RefundAttempt.class), org.mockito.ArgumentMatchers.eq(NOW));
        verify(refundAttemptRepository).saveAndFlush(any(RefundAttempt.class));
    }

    @Test
    void failedAttemptLeavesRefundAndPaymentPending() {
        arrangeLockedAggregate();
        ExecuteRefundRequest request = request(RefundExecutionOutcome.FAILED);
        when(executor.execute(any())).thenReturn(new RefundExecutionResult(
                RefundExecutionOutcome.FAILED,
                "SIMULATOR",
                null,
                "SIMULATED_FAILURE",
                NOW,
                "Failed"
        ));

        service.execute(refundId, requestKey, request);

        verify(payment, never()).recordFullRefund(any());
        verify(refund, never()).completeWith(any(), any());
        verify(refundAttemptRepository).saveAndFlush(any(RefundAttempt.class));
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejectedBeforeLocking() {
        ExecuteRefundRequest original = request(RefundExecutionOutcome.FAILED);
        RefundAttempt existing = org.mockito.Mockito.mock(RefundAttempt.class);
        when(existing.getPayloadHash()).thenReturn(RefundExecutionPayloadHasher.sha256(refundId, original));
        when(refundAttemptRepository.findByRefundIdAndRequestKey(refundId, requestKey))
                .thenReturn(Optional.of(existing));

        ExecuteRefundRequest changed = new ExecuteRefundRequest(
                0L, RefundExecutionMode.SIMULATOR, RefundExecutionOutcome.SUCCEEDED,
                null, null, "Changed payload"
        );

        assertThatThrownBy(() -> service.execute(refundId, requestKey, changed))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(RefundErrorCode.REQUEST_CONFLICT));
        verify(currentProfileProvider, never()).requireProfile();
    }

    @Test
    void sameKeyWithSamePayloadReplaysWithoutExecutingAgain() {
        ExecuteRefundRequest request = request(RefundExecutionOutcome.FAILED);
        RefundAttempt existing = org.mockito.Mockito.mock(RefundAttempt.class);
        when(existing.getPayloadHash()).thenReturn(RefundExecutionPayloadHasher.sha256(refundId, request));
        when(existing.getRefund()).thenReturn(refund);
        when(refund.getId()).thenReturn(refundId);
        when(refundRepository.findById(refundId)).thenReturn(Optional.of(refund));
        when(refundAttemptRepository.findByRefundIdAndRequestKey(refundId, requestKey))
                .thenReturn(Optional.of(existing));
        when(refundAttemptRepository.findByRefundIdOrderBySequenceNoAsc(refundId)).thenReturn(List.of(existing));
        when(assembler.assemble(refund, List.of(existing))).thenReturn(response);

        org.assertj.core.api.Assertions.assertThat(service.execute(refundId, requestKey, request))
                .isSameAs(response);
        verify(executorRegistry, never()).require(any());
        verify(currentProfileProvider, never()).requireProfile();
        verify(refundAttemptRepository, never()).saveAndFlush(any());
    }

    @Test
    void staleVersionIsRejectedWithoutCreatingAttempt() {
        arrangeLockedAggregate();
        when(refund.getVersion()).thenReturn(2L);

        assertThatThrownBy(() -> service.execute(refundId, requestKey, request(RefundExecutionOutcome.FAILED)))
                .isInstanceOfSatisfying(AppException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getErrorCode())
                                .isEqualTo(RefundErrorCode.VERSION_CONFLICT));
        verify(refundAttemptRepository, never()).saveAndFlush(any());
    }

    private void arrangeLockedAggregate() {
        UUID actorId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();

        when(executorRegistry.require(RefundExecutionMode.SIMULATOR)).thenReturn(executor);
        when(currentProfileProvider.requireProfile()).thenReturn(actor);
        when(actor.getId()).thenReturn(actorId);
        when(userProfileRepository.findByIdWithLock(actorId)).thenReturn(Optional.of(actor));
        when(refundRepository.findExecutionRouteById(refundId)).thenReturn(Optional.of(route));
        when(route.getConnectorId()).thenReturn(connectorId);
        when(route.getBookingId()).thenReturn(bookingId);
        when(route.getPaymentId()).thenReturn(paymentId);
        when(route.getSourcePaymentTransactionId()).thenReturn(sourceId);
        when(connectorRepository.findByIdWithLock(connectorId)).thenReturn(Optional.of(connector));
        when(bookingRepository.findByIdWithLock(bookingId)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByIdWithLock(paymentId)).thenReturn(Optional.of(payment));
        when(paymentTransactionRepository.findByIdWithLock(sourceId)).thenReturn(Optional.of(source));
        when(refundRepository.findByIdWithLock(refundId)).thenReturn(Optional.of(refund));
        lenient().when(refund.getId()).thenReturn(refundId);
        lenient().when(refund.getVersion()).thenReturn(0L);
        lenient().when(refund.getStatus()).thenReturn(RefundStatus.PENDING);
        lenient().when(refund.getAmount()).thenReturn(new BigDecimal("120000.00"));
        lenient().when(refundAttemptRepository.countByRefundId(refundId)).thenReturn(0L);
        lenient().when(refundAttemptRepository.findByRefundIdOrderBySequenceNoAsc(refundId)).thenReturn(List.of());
        lenient().when(assembler.assemble(refund, List.of())).thenReturn(response);
    }

    private ExecuteRefundRequest request(RefundExecutionOutcome outcome) {
        return new ExecuteRefundRequest(
                0L,
                RefundExecutionMode.SIMULATOR,
                outcome,
                null,
                null,
                outcome == RefundExecutionOutcome.SUCCEEDED ? "Succeeded" : "Failed"
        );
    }
}

