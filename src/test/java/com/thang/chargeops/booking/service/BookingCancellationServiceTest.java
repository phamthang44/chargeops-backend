package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.command.BookingCommand;
import com.thang.chargeops.booking.command.BookingCommandOperation;
import com.thang.chargeops.booking.command.BookingCommandPayloadHasher;
import com.thang.chargeops.booking.command.BookingCommandRegistry;
import com.thang.chargeops.booking.command.CancelBookingCanonicalPayload;
import com.thang.chargeops.booking.dto.request.CancelBookingRequest;
import com.thang.chargeops.booking.dto.response.BookingCancellationReason;
import com.thang.chargeops.booking.dto.response.BookingDetailResponse;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.enums.CancellationRefundTier;
import com.thang.chargeops.booking.exception.BookingCancellationDomainException;
import com.thang.chargeops.booking.exception.violation.BookingCancellationViolation;
import com.thang.chargeops.booking.history.BookingStatusActorType;
import com.thang.chargeops.booking.history.BookingStatusHistoryRecorder;
import com.thang.chargeops.booking.history.BookingStatusReason;
import com.thang.chargeops.booking.policy.BookingCancellationPolicy;
import com.thang.chargeops.booking.policy.model.CancellationRefundContext;
import com.thang.chargeops.booking.policy.model.CancellationRefundDecision;
import com.thang.chargeops.booking.projection.BookingCancellationRouteProjection;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.impl.BookingCancellationServiceImpl;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.BookingErrorCode;
import com.thang.chargeops.exception.errorcode.CommandErrorCode;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.exception.errorcode.RefundErrorCode;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.refund.model.CreateRefundObligationCommand;
import com.thang.chargeops.refund.model.RefundBasisType;
import com.thang.chargeops.refund.model.RefundReason;
import com.thang.chargeops.refund.service.RefundObligationService;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.repository.ConnectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingCancellationServiceTest {

    private static final UUID DRIVER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_DRIVER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BOOKING_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CONNECTOR_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID PAYMENT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID RECEIPT_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID COMMAND_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID REQUEST_KEY = UUID.fromString("88888888-8888-8888-8888-888888888888");

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");
    private static final String POLICY_VERSION = "booking-v4.9";
    private static final BigDecimal PACKAGE_AMOUNT = new BigDecimal("126000.00");

    @Mock private CurrentProfileProvider currentProfileProvider;
    @Mock private UserProfileRepository userProfileRepository;
    @Mock private ConnectorRepository connectorRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentTransactionRepository paymentTransactionRepository;
    @Mock private BookingCommandRegistry bookingCommandRegistry;
    @Mock private BookingStatusHistoryRecorder bookingStatusHistoryRecorder;
    @Mock private BookingCancellationPolicy bookingCancellationPolicy;
    @Mock private RefundObligationService refundObligationService;
    @Mock private DriverBookingDetailAssembler driverBookingDetailAssembler;

    private Clock applicationClock;
    private BookingCancellationServiceImpl service;

    private UserProfile driver;
    private Connector connector;
    private Booking booking;
    private Payment payment;
    private BookingCommand command;
    private BookingDetailResponse detailResponse;

    @BeforeEach
    void setUp() {
        applicationClock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new BookingCancellationServiceImpl(
                currentProfileProvider,
                userProfileRepository,
                connectorRepository,
                bookingRepository,
                paymentRepository,
                paymentTransactionRepository,
                bookingCommandRegistry,
                bookingStatusHistoryRecorder,
                bookingCancellationPolicy,
                refundObligationService,
                driverBookingDetailAssembler,
                applicationClock
        );

        driver = mock(UserProfile.class);
        lenient().when(driver.getId()).thenReturn(DRIVER_ID);
        lenient().when(currentProfileProvider.requireProfile()).thenReturn(driver);

        connector = mock(Connector.class);
        booking = mock(Booking.class);
        payment = mock(Payment.class);
        command = mock(BookingCommand.class);
        detailResponse = mock(BookingDetailResponse.class);

        lenient().when(command.getId()).thenReturn(COMMAND_ID);
    }

    private BookingCancellationRouteProjection createRoute(UUID bId, UUID dId, UUID cId) {
        return new BookingCancellationRouteProjection() {
            @Override public UUID getBookingId() { return bId; }
            @Override public UUID getDriverId() { return dId; }
            @Override public UUID getConnectorId() { return cId; }
        };
    }

    private void setupStandardSuccessfulFlow(BookingStatus status, long refundAmountLong) {
        lenient().when(bookingCommandRegistry.findReplay(eq(DRIVER_ID), eq(BookingCommandOperation.CANCEL_BOOKING), eq(REQUEST_KEY), any()))
                .thenReturn(Optional.empty());

        lenient().when(userProfileRepository.findByIdWithLock(DRIVER_ID)).thenReturn(Optional.of(driver));
        lenient().when(bookingRepository.findCancellationRouteById(BOOKING_ID))
                .thenReturn(Optional.of(createRoute(BOOKING_ID, DRIVER_ID, CONNECTOR_ID)));
        lenient().when(connectorRepository.findByIdWithLock(CONNECTOR_ID)).thenReturn(Optional.of(connector));
        lenient().when(bookingRepository.findByIdAndDriverIdWithLock(BOOKING_ID, DRIVER_ID)).thenReturn(Optional.of(booking));
        lenient().when(paymentRepository.findByBookingIdWithLock(BOOKING_ID)).thenReturn(Optional.of(payment));

        lenient().when(booking.getStatus()).thenReturn(status);
        lenient().when(booking.getVersion()).thenReturn(1L);
        lenient().when(booking.getPolicyVersion()).thenReturn(POLICY_VERSION);

        if (status == BookingStatus.PENDING) {
            lenient().when(booking.getExpiresAt()).thenReturn(NOW.plusSeconds(300));
        } else {
            lenient().when(booking.getCheckedInAt()).thenReturn(null);
            lenient().when(booking.getCheckInDeadline()).thenReturn(NOW.plusSeconds(600));
            lenient().when(booking.getPaymentConfirmedAt()).thenReturn(NOW.minusSeconds(300));
            lenient().when(booking.getStartAt()).thenReturn(NOW.plusSeconds(1800));
            lenient().when(booking.getFreeCancellationDeadline()).thenReturn(NOW.plusSeconds(600));
            lenient().when(payment.getAmount()).thenReturn(PACKAGE_AMOUNT);
            lenient().when(payment.getRefundAmount()).thenReturn(BigDecimal.ZERO);
        }

        CancellationRefundDecision decision = new CancellationRefundDecision(
                refundAmountLong > 0 ? CancellationRefundTier.GRACE : CancellationRefundTier.NONE,
                refundAmountLong > 0 ? 100 : 0,
                BigDecimal.valueOf(refundAmountLong),
                BigDecimal.ZERO,
                NOW.plusSeconds(600)
        );
        lenient().when(bookingCancellationPolicy.calculateRefund(any(CancellationRefundContext.class), eq(NOW), eq(false)))
                .thenReturn(decision);

        if (refundAmountLong > 0) {
            lenient().when(payment.getStatus()).thenReturn(PaymentStatus.PAID);
            lenient().when(payment.getPaidAt()).thenReturn(NOW.minusSeconds(300));
            lenient().when(payment.getId()).thenReturn(PAYMENT_ID);
            lenient().when(paymentTransactionRepository.findAppliedReceiptIdsByPaymentId(PAYMENT_ID))
                    .thenReturn(List.of(RECEIPT_ID));
        }

        lenient().when(bookingCommandRegistry.recordSuccess(eq(driver), eq(BookingCommandOperation.CANCEL_BOOKING), eq(REQUEST_KEY), any(), eq(booking), eq(NOW)))
                .thenReturn(command);

        lenient().doAnswer(invocation -> {
            Consumer<Booking> mutator = invocation.getArgument(4);
            mutator.accept(booking);
            return null;
        }).when(bookingStatusHistoryRecorder).recordUserTransition(eq(command), eq(BookingStatusActorType.DRIVER), eq(BookingStatusReason.DRIVER_CANCELLED), eq(NOW), any());

        lenient().when(driverBookingDetailAssembler.assemble(eq(booking), eq(payment), eq(NOW)))
                .thenReturn(detailResponse);
    }

    @Nested
    @DisplayName("13.1. Consent & Time Boundaries")
    class ConsentAndTimeBoundariesTest {

        @Test
        @DisplayName("C01: CONFIRMED booking cancelled before deadline gets 100% refund obligation")
        void confirmedBeforeDeadline_creates100PercentRefundObligation() {
            setupStandardSuccessfulFlow(BookingStatus.CONFIRMED, 126000L);
            CancelBookingRequest request = new CancelBookingRequest(1L, 126000L, POLICY_VERSION);

            BookingDetailResponse result = service.cancelBooking(BOOKING_ID, REQUEST_KEY, request);

            assertThat(result).isSameAs(detailResponse);

            verify(booking).cancel(eq(BookingCancellationReason.DRIVER_CANCELLED.name()), eq(NOW));

            ArgumentCaptor<CreateRefundObligationCommand> captor = ArgumentCaptor.forClass(CreateRefundObligationCommand.class);
            verify(refundObligationService).createObligation(captor.capture());
            CreateRefundObligationCommand refundCmd = captor.getValue();
            assertThat(refundCmd.lockedBooking()).isSameAs(booking);
            assertThat(refundCmd.lockedPayment()).isSameAs(payment);
            assertThat(refundCmd.sourceReceiptId()).isEqualTo(RECEIPT_ID);
            assertThat(refundCmd.basisType()).isEqualTo(RefundBasisType.BOOKING_CANCELLATION);
            assertThat(refundCmd.basisId()).isEqualTo(COMMAND_ID);
            assertThat(refundCmd.reason()).isEqualTo(RefundReason.VOLUNTARY_GRACE);
            assertThat(refundCmd.lockedActor()).isSameAs(driver);
            assertThat(refundCmd.decisionAt()).isEqualTo(NOW);

            verify(bookingRepository).flush();
        }

        @Test
        @DisplayName("C02: CONFIRMED booking cancelled exactly at/after deadline gets 0 refund")
        void confirmedAtDeadline_zeroRefund_noRefundObligationCreated() {
            setupStandardSuccessfulFlow(BookingStatus.CONFIRMED, 0L);
            CancelBookingRequest request = new CancelBookingRequest(1L, 0L, POLICY_VERSION);

            BookingDetailResponse result = service.cancelBooking(BOOKING_ID, REQUEST_KEY, request);

            assertThat(result).isSameAs(detailResponse);
            verify(booking).cancel(eq(BookingCancellationReason.DRIVER_CANCELLED.name()), eq(NOW));
            verifyNoInteractions(refundObligationService);
            verify(bookingRepository).flush();
        }

        @Test
        @DisplayName("C03: Dialog opened before deadline but submitted after deadline -> 409 CANCELLATION_CHANGED")
        void consentRefundAmountMismatch_throws409WithCurrentBooking() {
            setupStandardSuccessfulFlow(BookingStatus.CONFIRMED, 0L);
            // Driver expected 126000 refund based on stale client clock, but server calculated 0
            CancelBookingRequest request = new CancelBookingRequest(1L, 126000L, POLICY_VERSION);

            assertThatThrownBy(() -> service.cancelBooking(BOOKING_ID, REQUEST_KEY, request))
                    .isInstanceOf(AppException.class)
                    .satisfies(ex -> {
                        AppException appEx = (AppException) ex;
                        assertThat(appEx.getErrorCode()).isEqualTo(BookingErrorCode.CANCELLATION_CHANGED);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> details = (Map<String, Object>) appEx.getDetails();
                        assertThat(details).containsKey("currentBooking");
                    });

            verify(booking, never()).cancel(any(), any());
            verify(bookingCommandRegistry, never()).recordSuccess(any(), any(), any(), any(), any(), any());
            verifyNoInteractions(refundObligationService);
        }

        @Test
        @DisplayName("C04: Version mismatch -> 409 CANCELLATION_CHANGED")
        void versionMismatch_throws409() {
            setupStandardSuccessfulFlow(BookingStatus.CONFIRMED, 126000L);
            // Expected version 0, but current booking version is 1
            CancelBookingRequest request = new CancelBookingRequest(0L, 126000L, POLICY_VERSION);

            assertThatThrownBy(() -> service.cancelBooking(BOOKING_ID, REQUEST_KEY, request))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.CANCELLATION_CHANGED);

            verify(booking, never()).cancel(any(), any());
        }

        @Test
        @DisplayName("C05: Policy version snapshot mismatch -> 409 CANCELLATION_CHANGED")
        void policyVersionMismatch_throws409() {
            setupStandardSuccessfulFlow(BookingStatus.CONFIRMED, 126000L);
            // Expected policy booking-v4.8, but booking policy snapshot is booking-v4.9
            CancelBookingRequest request = new CancelBookingRequest(1L, 126000L, "booking-v4.8");

            assertThatThrownBy(() -> service.cancelBooking(BOOKING_ID, REQUEST_KEY, request))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.CANCELLATION_CHANGED);

            verify(booking, never()).cancel(any(), any());
        }

        @Test
        @DisplayName("C06: Outside grace, driver consents to 0 refund -> cancelled without refund")
        void outsideGrace_consentZero_cancelledSuccessfully() {
            setupStandardSuccessfulFlow(BookingStatus.CONFIRMED, 0L);
            CancelBookingRequest request = new CancelBookingRequest(1L, 0L, POLICY_VERSION);

            BookingDetailResponse result = service.cancelBooking(BOOKING_ID, REQUEST_KEY, request);

            assertThat(result).isSameAs(detailResponse);
            verify(booking).cancel(eq(BookingCancellationReason.DRIVER_CANCELLED.name()), eq(NOW));
            verifyNoInteractions(refundObligationService);
        }
    }

    @Nested
    @DisplayName("13.2. Effective State Guards")
    class EffectiveStateGuardsTest {

        @Test
        @DisplayName("S01: PENDING booking with active hold cancelled successfully without refund")
        void pendingActiveHold_cancelledWithoutRefund() {
            setupStandardSuccessfulFlow(BookingStatus.PENDING, 0L);
            CancelBookingRequest request = new CancelBookingRequest(1L, 0L, POLICY_VERSION);

            BookingDetailResponse result = service.cancelBooking(BOOKING_ID, REQUEST_KEY, request);

            assertThat(result).isSameAs(detailResponse);
            verify(booking).cancel(eq(BookingCancellationReason.DRIVER_CANCELLED.name()), eq(NOW));
            verifyNoInteractions(refundObligationService);
        }

        @Test
        @DisplayName("S02: PENDING booking expired (decisionAt >= expiresAt) -> 409 HOLD_EXPIRED")
        void pendingExpired_throwsHoldExpired() {
            when(bookingCommandRegistry.findReplay(any(), any(), any(), any())).thenReturn(Optional.empty());
            when(userProfileRepository.findByIdWithLock(DRIVER_ID)).thenReturn(Optional.of(driver));
            when(bookingRepository.findCancellationRouteById(BOOKING_ID))
                    .thenReturn(Optional.of(createRoute(BOOKING_ID, DRIVER_ID, CONNECTOR_ID)));
            when(connectorRepository.findByIdWithLock(CONNECTOR_ID)).thenReturn(Optional.of(connector));
            when(bookingRepository.findByIdAndDriverIdWithLock(BOOKING_ID, DRIVER_ID)).thenReturn(Optional.of(booking));
            when(paymentRepository.findByBookingIdWithLock(BOOKING_ID)).thenReturn(Optional.of(payment));

            when(booking.getStatus()).thenReturn(BookingStatus.PENDING);
            // Expired 5 seconds ago
            when(booking.getExpiresAt()).thenReturn(NOW.minusSeconds(5));

            CancelBookingRequest request = new CancelBookingRequest(1L, 0L, POLICY_VERSION);

            assertThatThrownBy(() -> service.cancelBooking(BOOKING_ID, REQUEST_KEY, request))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.HOLD_EXPIRED);
        }

        @Test
        @DisplayName("S02b: PENDING booking missing hold deadline fails closed")
        void pendingMissingExpiry_throwsStateConflict() {
            setupStandardSuccessfulFlow(BookingStatus.PENDING, 0L);
            when(booking.getExpiresAt()).thenReturn(null);

            CancelBookingRequest request = new CancelBookingRequest(1L, 0L, POLICY_VERSION);

            assertThatThrownBy(() -> service.cancelBooking(BOOKING_ID, REQUEST_KEY, request))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.STATE_CONFLICT);
            verify(bookingCommandRegistry, never()).recordSuccess(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("S03: CONFIRMED booking past check-in deadline -> 409 CHECK_IN_CLOSED")
        void confirmedPastCheckInDeadline_throwsCheckInClosed() {
            when(bookingCommandRegistry.findReplay(any(), any(), any(), any())).thenReturn(Optional.empty());
            when(userProfileRepository.findByIdWithLock(DRIVER_ID)).thenReturn(Optional.of(driver));
            when(bookingRepository.findCancellationRouteById(BOOKING_ID))
                    .thenReturn(Optional.of(createRoute(BOOKING_ID, DRIVER_ID, CONNECTOR_ID)));
            when(connectorRepository.findByIdWithLock(CONNECTOR_ID)).thenReturn(Optional.of(connector));
            when(bookingRepository.findByIdAndDriverIdWithLock(BOOKING_ID, DRIVER_ID)).thenReturn(Optional.of(booking));
            when(paymentRepository.findByBookingIdWithLock(BOOKING_ID)).thenReturn(Optional.of(payment));

            when(booking.getStatus()).thenReturn(BookingStatus.CONFIRMED);
            when(booking.getCheckedInAt()).thenReturn(null);
            // Passed check-in deadline
            when(booking.getCheckInDeadline()).thenReturn(NOW.minusSeconds(1));

            CancelBookingRequest request = new CancelBookingRequest(1L, 0L, POLICY_VERSION);

            assertThatThrownBy(() -> service.cancelBooking(BOOKING_ID, REQUEST_KEY, request))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.CHECK_IN_CLOSED);
        }

        @Test
        @DisplayName("S03b: CONFIRMED booking missing check-in deadline fails closed")
        void confirmedMissingCheckInDeadline_throwsStateConflict() {
            setupStandardSuccessfulFlow(BookingStatus.CONFIRMED, 0L);
            when(booking.getCheckInDeadline()).thenReturn(null);

            CancelBookingRequest request = new CancelBookingRequest(1L, 0L, POLICY_VERSION);

            assertThatThrownBy(() -> service.cancelBooking(BOOKING_ID, REQUEST_KEY, request))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.STATE_CONFLICT);
            verify(bookingCommandRegistry, never()).recordSuccess(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("S03c: Domain policy violations are translated to stable API conflicts")
        void cancellationPolicyViolation_throwsStateConflict() {
            setupStandardSuccessfulFlow(BookingStatus.CONFIRMED, 0L);
            when(bookingCancellationPolicy.calculateRefund(any(), eq(NOW), eq(false)))
                    .thenThrow(new BookingCancellationDomainException(
                            BookingCancellationViolation.PAYMENT_CONFIRMATION_REQUIRED,
                            "missing confirmation"
                    ));

            CancelBookingRequest request = new CancelBookingRequest(1L, 0L, POLICY_VERSION);

            assertThatThrownBy(() -> service.cancelBooking(BOOKING_ID, REQUEST_KEY, request))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.STATE_CONFLICT);
            verify(bookingCommandRegistry, never()).recordSuccess(any(), any(), any(), any(), any(), any());
            verifyNoInteractions(refundObligationService);
        }

        @Test
        @DisplayName("S04: Booking already checked in -> 409 STATE_CONFLICT")
        void confirmedAlreadyCheckedIn_throwsStateConflict() {
            when(bookingCommandRegistry.findReplay(any(), any(), any(), any())).thenReturn(Optional.empty());
            when(userProfileRepository.findByIdWithLock(DRIVER_ID)).thenReturn(Optional.of(driver));
            when(bookingRepository.findCancellationRouteById(BOOKING_ID))
                    .thenReturn(Optional.of(createRoute(BOOKING_ID, DRIVER_ID, CONNECTOR_ID)));
            when(connectorRepository.findByIdWithLock(CONNECTOR_ID)).thenReturn(Optional.of(connector));
            when(bookingRepository.findByIdAndDriverIdWithLock(BOOKING_ID, DRIVER_ID)).thenReturn(Optional.of(booking));
            when(paymentRepository.findByBookingIdWithLock(BOOKING_ID)).thenReturn(Optional.of(payment));

            when(booking.getStatus()).thenReturn(BookingStatus.CONFIRMED);
            when(booking.getCheckedInAt()).thenReturn(NOW.minusSeconds(60));

            CancelBookingRequest request = new CancelBookingRequest(1L, 0L, POLICY_VERSION);

            assertThatThrownBy(() -> service.cancelBooking(BOOKING_ID, REQUEST_KEY, request))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.STATE_CONFLICT);
        }

        @Test
        @DisplayName("S05: Booking in terminal or active charging status -> 409 STATE_CONFLICT")
        void terminalStatus_throwsStateConflict() {
            when(bookingCommandRegistry.findReplay(any(), any(), any(), any())).thenReturn(Optional.empty());
            when(userProfileRepository.findByIdWithLock(DRIVER_ID)).thenReturn(Optional.of(driver));
            when(bookingRepository.findCancellationRouteById(BOOKING_ID))
                    .thenReturn(Optional.of(createRoute(BOOKING_ID, DRIVER_ID, CONNECTOR_ID)));
            when(connectorRepository.findByIdWithLock(CONNECTOR_ID)).thenReturn(Optional.of(connector));
            when(bookingRepository.findByIdAndDriverIdWithLock(BOOKING_ID, DRIVER_ID)).thenReturn(Optional.of(booking));
            when(paymentRepository.findByBookingIdWithLock(BOOKING_ID)).thenReturn(Optional.of(payment));

            when(booking.getStatus()).thenReturn(BookingStatus.COMPLETED);

            CancelBookingRequest request = new CancelBookingRequest(1L, 0L, POLICY_VERSION);

            assertThatThrownBy(() -> service.cancelBooking(BOOKING_ID, REQUEST_KEY, request))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.STATE_CONFLICT);
        }
    }

    @Nested
    @DisplayName("13.3. Idempotency")
    class IdempotencyTest {

        @Test
        @DisplayName("I01: Fast replay before lock returns cached detail without acquiring locks")
        void fastReplay_returnsCachedDetailImmediately() {
            CancelBookingRequest request = new CancelBookingRequest(1L, 126000L, POLICY_VERSION);
            CancelBookingCanonicalPayload payload = CancelBookingCanonicalPayload.builder()
                    .bookingId(BOOKING_ID)
                    .expectedVersion(1L)
                    .expectedRefundAmount(126000L)
                    .acceptedPolicyVersion(POLICY_VERSION)
                    .build();
            String hash = BookingCommandPayloadHasher.sha256(payload);

            when(bookingCommandRegistry.findReplay(DRIVER_ID, BookingCommandOperation.CANCEL_BOOKING, REQUEST_KEY, hash))
                    .thenReturn(Optional.of(BOOKING_ID));

            when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
            when(paymentRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(payment));
            when(driverBookingDetailAssembler.assemble(booking, payment, NOW)).thenReturn(detailResponse);

            BookingDetailResponse result = service.cancelBooking(BOOKING_ID, REQUEST_KEY, request);

            assertThat(result).isSameAs(detailResponse);
            // Verify NO locks were acquired
            verifyNoInteractions(userProfileRepository, connectorRepository);
            verify(bookingRepository, never()).findCancellationRouteById(any());
        }

        @Test
        @DisplayName("I02: Locked replay under actor lock returns detail without mutating")
        void lockedReplay_returnsDetailWithoutMutating() {
            CancelBookingRequest request = new CancelBookingRequest(1L, 126000L, POLICY_VERSION);
            CancelBookingCanonicalPayload payload = CancelBookingCanonicalPayload.builder()
                    .bookingId(BOOKING_ID)
                    .expectedVersion(1L)
                    .expectedRefundAmount(126000L)
                    .acceptedPolicyVersion(POLICY_VERSION)
                    .build();
            String hash = BookingCommandPayloadHasher.sha256(payload);

            // Fast replay returns empty, locked replay returns hit
            when(bookingCommandRegistry.findReplay(DRIVER_ID, BookingCommandOperation.CANCEL_BOOKING, REQUEST_KEY, hash))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(BOOKING_ID));

            when(userProfileRepository.findByIdWithLock(DRIVER_ID)).thenReturn(Optional.of(driver));
            when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
            when(paymentRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(payment));
            when(driverBookingDetailAssembler.assemble(booking, payment, NOW)).thenReturn(detailResponse);

            BookingDetailResponse result = service.cancelBooking(BOOKING_ID, REQUEST_KEY, request);

            assertThat(result).isSameAs(detailResponse);
            verify(userProfileRepository).findByIdWithLock(DRIVER_ID);
            // Verify connector lock and route lookup were NOT performed
            verify(bookingRepository, never()).findCancellationRouteById(any());
            verifyNoInteractions(connectorRepository);
        }

        @Test
        @DisplayName("I03: Key reused with different payload propagates KEY_REUSED")
        void keyReusedWithDifferentPayload_propagatesException() {
            CancelBookingRequest request = new CancelBookingRequest(1L, 126000L, POLICY_VERSION);

            when(bookingCommandRegistry.findReplay(any(), any(), any(), any()))
                    .thenThrow(new AppException(CommandErrorCode.KEY_REUSED));

            assertThatThrownBy(() -> service.cancelBooking(BOOKING_ID, REQUEST_KEY, request))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", CommandErrorCode.KEY_REUSED);

            verifyNoInteractions(userProfileRepository);
        }
    }

    @Nested
    @DisplayName("13.4. Authorization & Scope")
    class AuthorizationAndScopeTest {

        @Test
        @DisplayName("A03: Driver attempting to cancel another driver's booking -> 403 BKG_NOT_ACCESS")
        void otherDriverBooking_throwsBookingNotAccess() {
            when(bookingCommandRegistry.findReplay(any(), any(), any(), any())).thenReturn(Optional.empty());
            when(userProfileRepository.findByIdWithLock(DRIVER_ID)).thenReturn(Optional.of(driver));
            // Route belongs to OTHER_DRIVER_ID
            when(bookingRepository.findCancellationRouteById(BOOKING_ID))
                    .thenReturn(Optional.of(createRoute(BOOKING_ID, OTHER_DRIVER_ID, CONNECTOR_ID)));

            CancelBookingRequest request = new CancelBookingRequest(1L, 126000L, POLICY_VERSION);

            assertThatThrownBy(() -> service.cancelBooking(BOOKING_ID, REQUEST_KEY, request))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", BookingErrorCode.BOOKING_NOT_ACCESS);

            verifyNoInteractions(connectorRepository);
        }

        @Test
        @DisplayName("A04: Booking not found -> 404 RESOURCE_NOT_FOUND")
        void bookingNotFound_throwsResourceNotFound() {
            when(bookingCommandRegistry.findReplay(any(), any(), any(), any())).thenReturn(Optional.empty());
            when(userProfileRepository.findByIdWithLock(DRIVER_ID)).thenReturn(Optional.of(driver));
            when(bookingRepository.findCancellationRouteById(BOOKING_ID)).thenReturn(Optional.empty());

            CancelBookingRequest request = new CancelBookingRequest(1L, 126000L, POLICY_VERSION);

            assertThatThrownBy(() -> service.cancelBooking(BOOKING_ID, REQUEST_KEY, request))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.RESOURCE_NOT_FOUND);

            verifyNoInteractions(connectorRepository);
        }
    }

    @Nested
    @DisplayName("13.5. Refund Source & Error Handling")
    class RefundSourceAndErrorsTest {

        @Test
        @DisplayName("R02: CONFIRMED in grace but payment has no APPLIED receipts -> 409 EXECUTION_CONFLICT")
        void noAppliedReceipt_throwsExecutionConflict() {
            setupStandardSuccessfulFlow(BookingStatus.CONFIRMED, 126000L);
            // Simulate missing applied receipts
            when(paymentTransactionRepository.findAppliedReceiptIdsByPaymentId(PAYMENT_ID))
                    .thenReturn(List.of());

            CancelBookingRequest request = new CancelBookingRequest(1L, 126000L, POLICY_VERSION);

            assertThatThrownBy(() -> service.cancelBooking(BOOKING_ID, REQUEST_KEY, request))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", RefundErrorCode.EXECUTION_CONFLICT);

            verify(booking, never()).cancel(any(), any());
            verifyNoInteractions(refundObligationService);
        }

        @Test
        @DisplayName("R03: CONFIRMED in grace but payment is not PAID -> 409 EXECUTION_CONFLICT")
        void paymentNotPaid_throwsExecutionConflict() {
            setupStandardSuccessfulFlow(BookingStatus.CONFIRMED, 126000L);
            when(payment.getStatus()).thenReturn(PaymentStatus.PENDING);

            CancelBookingRequest request = new CancelBookingRequest(1L, 126000L, POLICY_VERSION);

            assertThatThrownBy(() -> service.cancelBooking(BOOKING_ID, REQUEST_KEY, request))
                    .isInstanceOf(AppException.class)
                    .hasFieldOrPropertyWithValue("errorCode", RefundErrorCode.EXECUTION_CONFLICT);

            verify(booking, never()).cancel(any(), any());
        }
    }
}
