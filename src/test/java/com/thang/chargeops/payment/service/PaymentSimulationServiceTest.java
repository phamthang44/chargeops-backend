package com.thang.chargeops.payment.service;

import com.thang.chargeops.booking.command.BookingCommandInFlightLock;
import com.thang.chargeops.booking.command.BookingCommandOperation;
import com.thang.chargeops.booking.command.BookingCommandRegistry;
import com.thang.chargeops.booking.dto.response.BookingDetailResponse;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.mapper.BookingMapper;
import com.thang.chargeops.booking.policy.DriverBookingReadPolicy;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.model.BookingReadSnapshot;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.PaymentApplicationClassification;
import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.exception.errorcode.PaymentErrorCode;
import com.thang.chargeops.payment.dto.request.SimulationRequest;
import com.thang.chargeops.payment.dto.response.SimulationResultResponse;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.entity.PaymentTransaction;
import com.thang.chargeops.payment.model.NormalizedReceipt;
import com.thang.chargeops.payment.model.OrderCheckout;
import com.thang.chargeops.payment.model.PaymentReceiptResult;
import com.thang.chargeops.payment.model.PendingPaymentSpec;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.payment.service.impl.PaymentSimulationServiceImpl;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentSimulationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-21T10:00:00Z");

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentConfirmationService paymentConfirmationService;
    @Mock
    private BookingCommandInFlightLock bookingCommandInFlightLock;
    @Mock
    private BookingCommandRegistry bookingCommandRegistry;
    @Mock
    private CurrentProfileProvider currentProfileProvider;
    @Mock
    private BookingMapper bookingMapper;
    @Mock
    private DriverBookingReadPolicy driverBookingReadPolicy;
    @Mock
    private PaymentTransactionRepository paymentTransactionRepository;

    private PaymentSimulationServiceImpl service;

    private UUID adminId;
    private UserProfile adminProfile;
    private UUID bookingId;
    private Booking booking;
    private Payment payment;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        service = new PaymentSimulationServiceImpl(
                bookingRepository,
                paymentRepository,
                paymentConfirmationService,
                bookingCommandInFlightLock,
                bookingCommandRegistry,
                currentProfileProvider,
                bookingMapper,
                driverBookingReadPolicy,
                paymentTransactionRepository,
                clock
        );

        adminId = UUID.randomUUID();
        adminProfile = mock(UserProfile.class);
        lenient().when(adminProfile.getId()).thenReturn(adminId);
        lenient().when(currentProfileProvider.requireProfile()).thenReturn(adminProfile);

        lenient().when(bookingCommandInFlightLock.executeWithLock(any(), any(), any(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(3)).get());

        bookingId = UUID.randomUUID();
        booking = mock(Booking.class);
        lenient().when(booking.getId()).thenReturn(bookingId);
        lenient().when(booking.getStatus()).thenReturn(BookingStatus.PENDING);
        lenient().when(booking.getExpiresAt()).thenReturn(NOW.plus(Duration.ofMinutes(10)));

        payment = Payment.createPending(new PendingPaymentSpec(
                booking,
                new BigDecimal("100000"),
                PaymentMethod.SIMULATOR,
                "SIMULATOR",
                "ACC_SIM",
                "VND"
        ));

        lenient().when(bookingRepository.findById(bookingId)).thenReturn(Optional.of(booking));
        lenient().when(paymentRepository.findByBookingId(bookingId)).thenReturn(Optional.of(payment));

        BookingReadSnapshot listSnapshot = mock(BookingReadSnapshot.class);
        lenient().when(listSnapshot.evaluatedAt()).thenReturn(NOW);
        lenient().when(driverBookingReadPolicy.snapshotForList(any(), any())).thenReturn(listSnapshot);
        BookingDetailResponse detail = mock(BookingDetailResponse.class);
        lenient().when(bookingMapper.toBookingDetailResponse(any(), any(), any())).thenReturn(detail);
    }

    private void bindCheckout(Payment p) {
        String paymentCode = p.getPaymentCode();
        p.bindOrder(new OrderCheckout(
                "SIM-ORDER-" + paymentCode,
                paymentCode,
                "VA-SIM-" + paymentCode,
                p.getAmount(),
                NOW.plus(Duration.ofMinutes(10)),
                "QR_" + paymentCode,
                "https://qr.com/" + paymentCode
        ), NOW);
    }

    private PaymentTransaction stubTransaction(
            String transactionRef,
            PaymentApplicationClassification classification,
            String applicationReason
    ) {
        PaymentTransaction transaction = mock(PaymentTransaction.class);
        lenient().when(transaction.getId()).thenReturn(UUID.randomUUID());
        lenient().when(transaction.getProvider()).thenReturn(payment.getProvider());
        lenient().when(transaction.getReceivingAccountRef()).thenReturn(payment.getReceivingAccountRef());
        lenient().when(transaction.getTransactionRef()).thenReturn(transactionRef);
        lenient().when(transaction.getAmount()).thenReturn(new BigDecimal("100000"));
        lenient().when(transaction.getCurrency()).thenReturn("VND");
        lenient().when(transaction.getReceivedAt()).thenReturn(NOW);
        lenient().when(transaction.getProviderPaidAt()).thenReturn(NOW);
        lenient().when(transaction.getApplicationClassification()).thenReturn(classification);
        lenient().when(transaction.getApplicationReason()).thenReturn(applicationReason);
        when(paymentTransactionRepository.findByProviderAndReceivingAccountRefAndTransactionRef(
                payment.getProvider(),
                payment.getReceivingAccountRef(),
                transactionRef
        )).thenReturn(Optional.of(transaction));
        return transaction;
    }

    @Test
    @DisplayName("SUCCESS: creates NormalizedReceipt and delegates to PaymentConfirmationService")
    void testSuccess_NormalReceiptApplied() {
        bindCheckout(payment);

        UUID requestKey = UUID.randomUUID();
        SimulationRequest request = new SimulationRequest(
                "TX-SIM-001",
                SimulationRequest.Outcome.SUCCESS,
                100000L,
                "VND",
                NOW
        );

        when(bookingCommandRegistry.findReplay(eq(adminId), eq(BookingCommandOperation.SIMULATE_PAYMENT), eq(requestKey), any()))
                .thenReturn(Optional.empty());

        UUID txId = UUID.randomUUID();
        PaymentReceiptResult appliedResult = new PaymentReceiptResult(
                PaymentReceiptResult.Status.APPLIED,
                null,
                txId,
                payment.getId(),
                booking.getId()
        );
        when(paymentConfirmationService.processReceipt(any(NormalizedReceipt.class)))
                .thenReturn(appliedResult);
        stubTransaction("TX-SIM-001", PaymentApplicationClassification.APPLIED, null);

        SimulationResultResponse response = service.simulate(bookingId, requestKey, request);

        assertThat(response).isNotNull();
        assertThat(response.outcome()).isEqualTo(SimulationRequest.Outcome.SUCCESS);
        assertThat(response.duplicateEvent()).isFalse();
        assertThat(response.receipt()).isNotNull();
        assertThat(response.receipt().transactionRef()).isEqualTo("TX-SIM-001");
        assertThat(response.receipt().classification())
                .isEqualTo(SimulationResultResponse.ReceiptClassification.APPLIED);
        assertThat(response.receipt().reconciliationStatus())
                .isEqualTo(SimulationResultResponse.ReconciliationStatus.RESOLVED);

        verify(paymentConfirmationService).processReceipt(argThat(r ->
                r.transactionRef().equals("TX-SIM-001")
                        && r.amount().compareTo(new BigDecimal("100000")) == 0
                        && r.paymentCode().equals(payment.getPaymentCode())
        ));
        verify(bookingCommandRegistry).recordSuccess(eq(adminProfile), eq(BookingCommandOperation.SIMULATE_PAYMENT), eq(requestKey), any(), eq(booking), any());
    }

    @Test
    @DisplayName("SUCCESS: identifies duplicateEvent=true when PaymentConfirmationService returns DUPLICATE")
    void testSuccess_DuplicateEvent() {
        bindCheckout(payment);

        UUID requestKey = UUID.randomUUID();
        SimulationRequest request = new SimulationRequest(
                "TX-SIM-002",
                SimulationRequest.Outcome.SUCCESS,
                100000L,
                "VND",
                NOW
        );

        when(bookingCommandRegistry.findReplay(eq(adminId), eq(BookingCommandOperation.SIMULATE_PAYMENT), eq(requestKey), any()))
                .thenReturn(Optional.empty());

        PaymentReceiptResult duplicateResult = new PaymentReceiptResult(
                PaymentReceiptResult.Status.DUPLICATE,
                null,
                UUID.randomUUID(),
                payment.getId(),
                booking.getId()
        );
        when(paymentConfirmationService.processReceipt(any(NormalizedReceipt.class)))
                .thenReturn(duplicateResult);
        stubTransaction(
                "TX-SIM-002",
                PaymentApplicationClassification.UNAPPLIED,
                "ALREADY_PAID"
        );

        SimulationResultResponse response = service.simulate(bookingId, requestKey, request);

        assertThat(response.outcome()).isEqualTo(SimulationRequest.Outcome.SUCCESS);
        assertThat(response.duplicateEvent()).isTrue();
    }

    @Test
    @DisplayName("SUCCESS: throws CHECKOUT_REQUIRED if payment has not been checked out yet")
    void testSuccess_MissingCheckoutThrowsConflict() {
        UUID requestKey = UUID.randomUUID();
        SimulationRequest request = new SimulationRequest(
                "TX-SIM-003",
                SimulationRequest.Outcome.SUCCESS,
                100000L,
                "VND",
                NOW
        );

        when(bookingCommandRegistry.findReplay(eq(adminId), eq(BookingCommandOperation.SIMULATE_PAYMENT), eq(requestKey), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.simulate(bookingId, requestKey, request))
                .isInstanceOf(AppException.class)
                .satisfies(e -> {
                    AppException appException = (AppException) e;
                    assertThat(appException.getErrorCode()).isEqualTo(PaymentErrorCode.CHECKOUT_REQUIRED);
                });

        verify(paymentConfirmationService, never()).processReceipt(any());
    }

    @Test
    @DisplayName("FAILED: marks payment FAILED without creating receipt or confirming booking")
    void testFailure_MarksPaymentFailed() {
        bindCheckout(payment);

        UUID requestKey = UUID.randomUUID();
        SimulationRequest request = new SimulationRequest(
                "TX-SIM-FAIL",
                SimulationRequest.Outcome.FAILED,
                100000L,
                "VND",
                NOW
        );

        when(bookingCommandRegistry.findReplay(eq(adminId), eq(BookingCommandOperation.SIMULATE_PAYMENT), eq(requestKey), any()))
                .thenReturn(Optional.empty());

        SimulationResultResponse response = service.simulate(bookingId, requestKey, request);

        assertThat(response.outcome()).isEqualTo(SimulationRequest.Outcome.FAILED);
        assertThat(response.duplicateEvent()).isFalse();
        assertThat(response.receipt()).isNull();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);

        verify(paymentConfirmationService, never()).processReceipt(any());
        verify(bookingCommandRegistry).recordSuccess(eq(adminProfile), eq(BookingCommandOperation.SIMULATE_PAYMENT), eq(requestKey), any(), eq(booking), any());
    }

    @Test
    @DisplayName("TIMEOUT: throws CHECKOUT_UNAVAILABLE (503) without saving DB changes")
    void testTimeout_ThrowsCheckoutUnavailable503() {
        bindCheckout(payment);

        UUID requestKey = UUID.randomUUID();
        SimulationRequest request = new SimulationRequest(
                "TX-SIM-TIMEOUT",
                SimulationRequest.Outcome.TIMEOUT,
                100000L,
                "VND",
                NOW
        );

        when(bookingCommandRegistry.findReplay(eq(adminId), eq(BookingCommandOperation.SIMULATE_PAYMENT), eq(requestKey), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.simulate(bookingId, requestKey, request))
                .isInstanceOf(AppException.class)
                .satisfies(e -> {
                    AppException appException = (AppException) e;
                    assertThat(appException.getErrorCode().getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });

        verify(paymentConfirmationService, never()).processReceipt(any());
        verify(bookingCommandRegistry, never()).recordSuccess(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Replay: returns previous execution with duplicateEvent=true")
    void testIdempotentReplay() {
        bindCheckout(payment);

        UUID requestKey = UUID.randomUUID();
        SimulationRequest request = new SimulationRequest(
                "TX-REPLAY",
                SimulationRequest.Outcome.SUCCESS,
                100000L,
                "VND",
                NOW
        );

        when(bookingCommandRegistry.findReplay(eq(adminId), eq(BookingCommandOperation.SIMULATE_PAYMENT), eq(requestKey), any()))
                .thenReturn(Optional.of(bookingId));

        stubTransaction("TX-REPLAY", PaymentApplicationClassification.APPLIED, null);

        SimulationResultResponse response = service.simulate(bookingId, requestKey, request);

        assertThat(response.outcome()).isEqualTo(SimulationRequest.Outcome.SUCCESS);
        assertThat(response.duplicateEvent()).isTrue();
        assertThat(response.receipt()).isNotNull();

        verify(paymentConfirmationService, never()).processReceipt(any());
    }

    @Test
    @DisplayName("Replay loads the booking recorded by the original command")
    void replayUsesRecordedBookingId() {
        UUID originalBookingId = UUID.randomUUID();
        UUID requestKey = UUID.randomUUID();
        SimulationRequest request = new SimulationRequest(
                "TX-REPLAY-OTHER-BOOKING",
                SimulationRequest.Outcome.FAILED,
                100000L,
                "VND",
                NOW
        );

        Booking originalBooking = mock(Booking.class);
        when(originalBooking.getStatus()).thenReturn(BookingStatus.PENDING);
        when(originalBooking.getExpiresAt()).thenReturn(NOW.plus(Duration.ofMinutes(10)));
        Payment originalPayment = Payment.createPending(new PendingPaymentSpec(
                originalBooking,
                new BigDecimal("100000"),
                PaymentMethod.SIMULATOR,
                "SIMULATOR",
                "ACC_SIM",
                "VND"
        ));
        bindCheckout(originalPayment);

        when(bookingCommandRegistry.findReplay(
                eq(adminId),
                eq(BookingCommandOperation.SIMULATE_PAYMENT),
                eq(requestKey),
                any()
        )).thenReturn(Optional.of(originalBookingId));
        when(bookingRepository.findById(originalBookingId)).thenReturn(Optional.of(originalBooking));
        when(paymentRepository.findByBookingId(originalBookingId)).thenReturn(Optional.of(originalPayment));

        SimulationResultResponse response = service.simulate(bookingId, requestKey, request);

        assertThat(response.duplicateEvent()).isTrue();
        verify(bookingRepository).findById(originalBookingId);
        verify(bookingRepository, never()).findById(bookingId);
    }

    @Test
    @DisplayName("Idempotency payload hash is scoped to the booking")
    void idempotencyHashIncludesBookingId() {
        bindCheckout(payment);
        UUID otherBookingId = UUID.randomUUID();
        Booking otherBooking = mock(Booking.class);
        when(otherBooking.getStatus()).thenReturn(BookingStatus.PENDING);
        when(otherBooking.getExpiresAt()).thenReturn(NOW.plus(Duration.ofMinutes(10)));
        Payment otherPayment = Payment.createPending(new PendingPaymentSpec(
                otherBooking,
                new BigDecimal("100000"),
                PaymentMethod.SIMULATOR,
                "SIMULATOR",
                "ACC_SIM",
                "VND"
        ));
        bindCheckout(otherPayment);
        when(bookingRepository.findById(otherBookingId)).thenReturn(Optional.of(otherBooking));
        when(paymentRepository.findByBookingId(otherBookingId)).thenReturn(Optional.of(otherPayment));

        SimulationRequest request = new SimulationRequest(
                "TX-HASH",
                SimulationRequest.Outcome.FAILED,
                100000L,
                "VND",
                NOW
        );

        service.simulate(bookingId, UUID.randomUUID(), request);
        service.simulate(otherBookingId, UUID.randomUUID(), request);

        ArgumentCaptor<String> payloadHash = ArgumentCaptor.forClass(String.class);
        verify(bookingCommandRegistry, times(2)).findReplay(
                eq(adminId),
                eq(BookingCommandOperation.SIMULATE_PAYMENT),
                any(),
                payloadHash.capture()
        );
        assertThat(payloadHash.getAllValues()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("SUCCESS rejects a zero amount with domain error")
    void successRejectsZeroAmount() {
        bindCheckout(payment);
        UUID requestKey = UUID.randomUUID();
        SimulationRequest request = new SimulationRequest(
                "TX-ZERO",
                SimulationRequest.Outcome.SUCCESS,
                0L,
                "VND",
                NOW
        );

        when(bookingCommandRegistry.findReplay(
                eq(adminId),
                eq(BookingCommandOperation.SIMULATE_PAYMENT),
                eq(requestKey),
                any()
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.simulate(bookingId, requestKey, request))
                .isInstanceOf(AppException.class)
                .satisfies(error -> assertThat(((AppException) error).getErrorCode())
                        .isEqualTo(PaymentErrorCode.AMOUNT_INVALID));

        verify(paymentConfirmationService, never()).processReceipt(any());
    }

    @Test
    @DisplayName("Throws RESOURCE_NOT_FOUND when booking does not exist")
    void testBookingNotFound() {
        UUID unknownBooking = UUID.randomUUID();
        UUID requestKey = UUID.randomUUID();
        SimulationRequest request = new SimulationRequest(
                "TX-UNKNOWN",
                SimulationRequest.Outcome.SUCCESS,
                100000L,
                "VND",
                NOW
        );

        when(bookingRepository.findById(unknownBooking)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.simulate(unknownBooking, requestKey, request))
                .isInstanceOf(AppException.class)
                .satisfies(e -> {
                    AppException appException = (AppException) e;
                    assertThat(appException.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND);
                });
    }
}
