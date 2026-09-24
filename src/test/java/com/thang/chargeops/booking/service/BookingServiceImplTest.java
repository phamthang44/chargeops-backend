package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.command.BookingCommand;
import com.thang.chargeops.booking.command.BookingCommandInFlightLock;
import com.thang.chargeops.booking.command.BookingCommandOperation;
import com.thang.chargeops.booking.command.BookingCommandRegistry;
import com.thang.chargeops.booking.config.BookingPolicyConfig;
import com.thang.chargeops.booking.dto.filter.DriverBookingHistoryFilter;
import com.thang.chargeops.booking.dto.request.CreateBookingRequest;
import com.thang.chargeops.booking.dto.response.BookingDetailResponse;
import com.thang.chargeops.booking.dto.response.BookingPolicyResponse;
import com.thang.chargeops.booking.dto.response.BookingStatsResponse;
import com.thang.chargeops.booking.dto.response.CheckoutResponse;
import com.thang.chargeops.booking.dto.response.CreateBookingResponse;
import com.thang.chargeops.booking.dto.response.DriverBookingListItemResponse;
import com.thang.chargeops.booking.dto.response.PricePreviewResponse;
import com.thang.chargeops.booking.projection.BookingCompletedSessionProjection;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.history.BookingStatusActorType;
import com.thang.chargeops.booking.history.BookingStatusHistoryRecorder;
import com.thang.chargeops.booking.mapper.BookingMapper;
import com.thang.chargeops.booking.policy.DriverBookingReadPolicy;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.impl.BookingServiceImpl;
import com.thang.chargeops.booking.service.model.BookingReadSnapshot;
import com.thang.chargeops.booking.service.model.DriverBookingHistoryResult;
import com.thang.chargeops.booking.service.model.HoldPreparationContext;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.PaymentApplicationClassification;
import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.entity.PaymentTransaction;
import com.thang.chargeops.payment.gateway.PaymentGatewayRegistry;
import com.thang.chargeops.payment.gateway.PaymentGatewayProfile;
import com.thang.chargeops.payment.model.OrderCheckout;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.BookingErrorCode;
import com.thang.chargeops.exception.errorcode.CommandErrorCode;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.Station;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingServiceImplTest {

    private static final UUID DRIVER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONNECTOR_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID REQUEST_KEY = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID BOOKING_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant DECISION_AT = Instant.parse("2026-09-16T02:00:00Z");
    private static final Instant START_AT = Instant.parse("2026-09-16T03:00:00Z");
    private static final Instant END_AT = Instant.parse("2026-09-16T04:00:00Z");
    private static final long TOTAL_AMOUNT = 126_000L;
    private static final String PRICING_VERSION = "a".repeat(64);
    private static final String POLICY_VERSION = "v4.9";

    @Mock private BookingRepository bookingRepository;
    @Mock private BookingPricingService bookingPricingService;
    @Mock private BookingHoldCoordinator bookingHoldCoordinator;
    @Mock private CurrentProfileProvider currentProfileProvider;
    @Mock private BookingCommandRegistry bookingCommandRegistry;
    @Mock private BookingMapper bookingMapper;
    @Mock private BookingPolicyConfig bookingPolicyConfig;
    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentTransactionRepository paymentTransactionRepository;
    @Mock private com.thang.chargeops.refund.repository.RefundRepository refundRepository;
    @Mock private DriverBookingReadPolicy driverBookingReadPolicy;
    @Mock private BookingStatusHistoryRecorder bookingStatusHistoryRecorder;
    @Mock private PaymentGatewayRegistry paymentGatewayRegistry;
    @Mock private BookingCheckoutPersistence bookingCheckoutPersistence;
    @Mock private BookingCommandInFlightLock bookingCommandInFlightLock;
    @Mock private Clock applicationClock;

    private DriverBookingDetailAssembler driverBookingDetailAssembler;
    private BookingServiceImpl service;
    private UserProfile driver;
    private Connector connector;
    private ChargePoint chargePoint;
    private Station station;
    private BookingPolicyResponse policy;
    private PricePreviewResponse currentPrice;

    @BeforeEach
    void setUp() {
        driverBookingDetailAssembler = new DriverBookingDetailAssembler(
                driverBookingReadPolicy,
                paymentTransactionRepository,
                refundRepository,
                bookingMapper
        );
        service = new BookingServiceImpl(
                bookingRepository,
                bookingPricingService,
                bookingHoldCoordinator,
                currentProfileProvider,
                bookingCommandRegistry,
                bookingMapper,
                bookingPolicyConfig,
                paymentRepository,
                driverBookingDetailAssembler,
                driverBookingReadPolicy,
                bookingStatusHistoryRecorder,
                paymentGatewayRegistry,
                bookingCheckoutPersistence,
                bookingCommandInFlightLock,
                applicationClock
        );

        driver = mock(UserProfile.class);
        connector = mock(Connector.class);
        chargePoint = mock(ChargePoint.class);
        station = mock(Station.class);
        policy = mock(BookingPolicyResponse.class);

        when(driver.getId()).thenReturn(DRIVER_ID);
        lenient().when(bookingCommandInFlightLock.executeWithLock(any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    java.util.function.Supplier<?> action = invocation.getArgument(3);
                    return action != null ? action.get() : null;
                });
        lenient().when(paymentGatewayRegistry.profile(PaymentMethod.SIMULATOR))
                .thenReturn(new PaymentGatewayProfile("SIMULATOR", "SIMULATOR", "VND"));

        currentPrice = new PricePreviewResponse(
                PRICING_VERSION,
                CONNECTOR_ID,
                START_AT,
                END_AT,
                60,
                "VND",
                TOTAL_AMOUNT,
                List.of(),
                null,
                policy,
                List.of()
        );
    }

    @Test
    void createsCheckoutOutsidePersistenceTransactionAndStoresTheResult() {
        Payment payment = mock(Payment.class);
        OrderCheckout orderCheckout = mock(OrderCheckout.class);
        CheckoutResponse expected = new CheckoutResponse(
                com.thang.chargeops.common.enums.CheckoutStatus.READY,
                PaymentMethod.SIMULATOR,
                DECISION_AT.plusSeconds(600),
                "Pay in simulator",
                "SIM-ORDER",
                "https://simulator.test/checkout"
        );

        when(currentProfileProvider.requireProfile()).thenReturn(driver);
        when(applicationClock.instant()).thenReturn(
                DECISION_AT,
                DECISION_AT.plusSeconds(1)
        );
        when(bookingCommandRegistry.findReplay(
                eq(DRIVER_ID),
                eq(BookingCommandOperation.CREATE_CHECKOUT),
                eq(REQUEST_KEY),
                anyString()
        )).thenReturn(Optional.empty());
        when(bookingCheckoutPersistence.prepareCheckout(
                BOOKING_ID,
                DRIVER_ID,
                DECISION_AT
        )).thenReturn(payment);
        when(payment.getProviderOrderRef()).thenReturn(null);
        when(paymentGatewayRegistry.createCheckout(payment, DECISION_AT))
                .thenReturn(orderCheckout);
        when(bookingCheckoutPersistence.completeCheckout(
                eq(BOOKING_ID),
                eq(DRIVER_ID),
                eq(REQUEST_KEY),
                anyString(),
                eq(orderCheckout),
                eq(DECISION_AT.plusSeconds(1))
        )).thenReturn(expected);

        assertThat(service.createCheckout(BOOKING_ID, REQUEST_KEY))
                .isSameAs(expected);

        var order = inOrder(
                bookingCheckoutPersistence,
                paymentGatewayRegistry
        );
        order.verify(bookingCheckoutPersistence).prepareCheckout(
                BOOKING_ID,
                DRIVER_ID,
                DECISION_AT
        );
        order.verify(paymentGatewayRegistry).createCheckout(
                payment,
                DECISION_AT
        );
        order.verify(bookingCheckoutPersistence).completeCheckout(
                eq(BOOKING_ID),
                eq(DRIVER_ID),
                eq(REQUEST_KEY),
                anyString(),
                eq(orderCheckout),
                eq(DECISION_AT.plusSeconds(1))
        );
    }

    @Test
    void checkoutReplayReturnsStoredResultWithoutCallingGateway() {
        CheckoutResponse expected = new CheckoutResponse(
                com.thang.chargeops.common.enums.CheckoutStatus.READY,
                PaymentMethod.SIMULATOR,
                DECISION_AT.plusSeconds(600),
                null,
                "SIM-ORDER",
                "https://simulator.test/checkout"
        );
        when(currentProfileProvider.requireProfile()).thenReturn(driver);
        when(applicationClock.instant()).thenReturn(DECISION_AT);
        when(bookingCommandRegistry.findReplay(
                eq(DRIVER_ID),
                eq(BookingCommandOperation.CREATE_CHECKOUT),
                eq(REQUEST_KEY),
                anyString()
        )).thenReturn(Optional.of(BOOKING_ID));
        when(bookingCheckoutPersistence.loadReplay(
                BOOKING_ID,
                DRIVER_ID,
                DECISION_AT
        )).thenReturn(expected);

        assertThat(service.createCheckout(BOOKING_ID, REQUEST_KEY))
                .isSameAs(expected);

        verifyNoInteractions(paymentGatewayRegistry);
        verify(bookingCheckoutPersistence, never()).prepareCheckout(
                any(), any(), any()
        );
    }

    @Test
    void createsBookingPaymentCommandAndHistoryInOneFlow() {
        CreateBookingRequest request = request(TOTAL_AMOUNT, PRICING_VERSION, POLICY_VERSION);
        CreateBookingResponse expected = CreateBookingResponse.builder().bookingId(BOOKING_ID).build();
        BookingCommand command = mock(BookingCommand.class);

        when(connector.getConnectorCode()).thenReturn("CON-01");
        when(connector.getChargePoint()).thenReturn(chargePoint);
        when(chargePoint.getChargePointCode()).thenReturn("CP-01");
        when(chargePoint.getStation()).thenReturn(station);
        when(station.getName()).thenReturn("ChargeOps Station");
        when(station.getAddressLine()).thenReturn("1 Test Street");
        when(policy.policyVersion()).thenReturn(POLICY_VERSION);
        when(policy.checkInCloseBeforeEndMin()).thenReturn(15);
        when(currentProfileProvider.requireProfile()).thenReturn(driver);
        when(bookingCommandRegistry.findReplay(any(), any(), any(), anyString()))
                .thenReturn(Optional.empty());
        when(bookingHoldCoordinator.lockDriver(DRIVER_ID)).thenReturn(driver);
        when(bookingHoldCoordinator.prepareUnderLock(driver, CONNECTOR_ID, START_AT, END_AT))
                .thenReturn(new HoldPreparationContext(driver, connector, DECISION_AT));
        when(bookingPricingService.repriceUnderLock(driver, connector, START_AT, 60, DECISION_AT))
                .thenReturn(currentPrice);
        when(bookingPolicyConfig.getPaymentHoldMinutes()).thenReturn(10);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(bookingCommandRegistry.recordSuccess(
                eq(driver), eq(BookingCommandOperation.CREATE_BOOKING), eq(REQUEST_KEY),
                anyString(), any(Booking.class), eq(DECISION_AT)))
                .thenReturn(command);
        when(bookingMapper.toCreateBookingResponse(any(Booking.class), any(Payment.class)))
                .thenReturn(expected);

        CreateBookingResponse actual = service.createNewBooking(REQUEST_KEY, request);

        assertThat(actual).isSameAs(expected);
        ArgumentCaptor<Booking> bookingCaptor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(bookingCaptor.capture());
        Booking saved = bookingCaptor.getValue();
        assertThat(saved.getBookingCode()).startsWith("BK-").hasSize(23);
        assertThat(saved.getExpiresAt()).isEqualTo(DECISION_AT.plusSeconds(600));
        assertThat(saved.getPolicyVersion()).isEqualTo(POLICY_VERSION);
        verify(paymentRepository).save(any(Payment.class));
        verify(bookingStatusHistoryRecorder).recordCreation(
                command, BookingStatusActorType.DRIVER, DECISION_AT);
    }

    @Test
    void committedReplayReturnsBeforeAnyLockOrRepricing() {
        CreateBookingRequest request = request(TOTAL_AMOUNT, PRICING_VERSION, POLICY_VERSION);
        Booking booking = mock(Booking.class);
        Payment payment = mock(Payment.class);
        CreateBookingResponse expected = CreateBookingResponse.builder().bookingId(BOOKING_ID).build();

        when(currentProfileProvider.requireProfile()).thenReturn(driver);
        when(bookingCommandRegistry.findReplay(
                eq(DRIVER_ID), eq(BookingCommandOperation.CREATE_BOOKING),
                eq(REQUEST_KEY), anyString()))
                .thenReturn(Optional.of(BOOKING_ID));
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(payment));
        when(bookingMapper.toCreateBookingResponse(booking, payment)).thenReturn(expected);

        assertThat(service.createNewBooking(REQUEST_KEY, request)).isSameAs(expected);

        verifyNoInteractions(bookingHoldCoordinator, bookingPricingService);
        verify(bookingRepository, never()).save(any());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void changedConsentDoesNotPersistAnything() {
        CreateBookingRequest request = request(TOTAL_AMOUNT - 1, PRICING_VERSION, POLICY_VERSION);

        when(currentProfileProvider.requireProfile()).thenReturn(driver);
        when(bookingCommandRegistry.findReplay(any(), any(), any(), anyString()))
                .thenReturn(Optional.empty());
        when(bookingHoldCoordinator.lockDriver(DRIVER_ID)).thenReturn(driver);
        when(bookingHoldCoordinator.prepareUnderLock(driver, CONNECTOR_ID, START_AT, END_AT))
                .thenReturn(new HoldPreparationContext(driver, connector, DECISION_AT));
        when(bookingPricingService.repriceUnderLock(driver, connector, START_AT, 60, DECISION_AT))
                .thenReturn(currentPrice);

        assertThatThrownBy(() -> service.createNewBooking(REQUEST_KEY, request))
                .isInstanceOf(com.thang.chargeops.exception.AppException.class);

        verify(bookingRepository, never()).save(any());
        verify(paymentRepository, never()).save(any());
        verify(bookingCommandRegistry, never()).recordSuccess(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(bookingStatusHistoryRecorder);
    }

    @Test
    void mapsActiveBookingsUsingOneEvaluationInstant() {
        Booking booking = mock(Booking.class);
        DriverBookingListItemResponse expected =
                DriverBookingListItemResponse.builder()
                        .bookingId(BOOKING_ID)
                        .build();
        PageRequest pageable = PageRequest.of(0, 20);

        when(currentProfileProvider.requireProfile()).thenReturn(driver);
        when(applicationClock.instant()).thenReturn(DECISION_AT);
        when(bookingRepository.findActiveForDriver(
                DRIVER_ID,
                DECISION_AT,
                pageable
        )).thenReturn(new PageImpl<>(List.of(booking), pageable, 1));
        when(bookingMapper.toDriverBookingListItemResponse(
                booking,
                DECISION_AT
        )).thenReturn(expected);

        var result = service.getMyActiveBookings(1, 20);

        assertThat(result.getContent()).containsExactly(expected);
        verify(bookingMapper).toDriverBookingListItemResponse(
                booking,
                DECISION_AT
        );
    }

    @Test
    void returnsHistoryPageAndQueryWideStatusCounts() {
        Booking booking = mock(Booking.class);
        DriverBookingListItemResponse expected =
                DriverBookingListItemResponse.builder()
                        .bookingId(BOOKING_ID)
                        .build();
        DriverBookingHistoryFilter filter = new DriverBookingHistoryFilter(
                "alpha",
                DriverBookingHistoryFilter.HistoryStatus.CANCELLED
        );
        PageRequest pageable = PageRequest.of(
                0,
                20,
                Sort.by(
                        Sort.Order.desc("startAt"),
                        Sort.Order.desc("id")
                )
        );

        when(currentProfileProvider.requireProfile()).thenReturn(driver);
        when(applicationClock.instant()).thenReturn(DECISION_AT);
        when(bookingRepository.findAll(
                any(Specification.class),
                eq(pageable)
        )).thenReturn(new PageImpl<>(List.of(booking), pageable, 3));
        when(bookingRepository.count(any(Specification.class)))
                .thenReturn(1L, 2L);
        when(bookingMapper.toDriverBookingListItemResponse(
                booking,
                DECISION_AT
        )).thenReturn(expected);

        DriverBookingHistoryResult result = service.getMyBookingHistory(
                filter,
                1,
                20
        );

        assertThat(result.page().getContent()).containsExactly(expected);
        assertThat(result.page().getNumber()).isZero();
        assertThat(result.page().getTotalElements()).isOne();
        assertThat(result.counts())
                .isEqualTo(Map.of(
                        "all", 3L,
                        "completed", 1L,
                        "cancelled", 2L
                ));
        verify(bookingRepository, times(2))
                .count(any(Specification.class));
        verify(bookingMapper).toDriverBookingListItemResponse(
                booking,
                DECISION_AT
        );
    }

    @Test
    void buildsDetailSnapshotFromPaymentAndReceipts() {
        Booking booking = mock(Booking.class);
        Payment payment = mock(Payment.class);
        PaymentTransaction appliedReceipt = mock(PaymentTransaction.class);
        PaymentTransaction unappliedReceipt = mock(PaymentTransaction.class);
        UUID paymentId = UUID.randomUUID();
        Instant checkoutExpiresAt = DECISION_AT.plusSeconds(300);
        BookingReadSnapshot listSnapshot = BookingReadSnapshot.forList(
                DECISION_AT,
                true
        );
        BookingDetailResponse expected = BookingDetailResponse.builder()
                .bookingId(BOOKING_ID)
                .build();

        when(currentProfileProvider.requireProfile()).thenReturn(driver);
        when(applicationClock.instant()).thenReturn(DECISION_AT);
        when(bookingRepository.findByIdAndDriverId(BOOKING_ID, DRIVER_ID))
                .thenReturn(Optional.of(booking));
        when(paymentRepository.findByBookingId(BOOKING_ID))
                .thenReturn(Optional.of(payment));
        when(payment.getId()).thenReturn(paymentId);
        when(payment.getCurrency()).thenReturn("VND");
        when(payment.getRefundAmount())
                .thenReturn(BigDecimal.valueOf(20_000));
        when(booking.getStatus()).thenReturn(BookingStatus.PENDING);
        when(booking.getExpiresAt()).thenReturn(checkoutExpiresAt);
        when(payment.getStatus()).thenReturn(PaymentStatus.PENDING);
        when(payment.getMethod()).thenReturn(PaymentMethod.BANK_TRANSFER);
        when(payment.getProviderOrderRef()).thenReturn("ORDER-1");
        when(payment.getProviderExpiresAt()).thenReturn(checkoutExpiresAt);
        when(payment.getQrCodeUrl()).thenReturn("https://qr.example/order-1");
        when(appliedReceipt.getAmount())
                .thenReturn(BigDecimal.valueOf(126_000));
        when(appliedReceipt.getApplicationClassification())
                .thenReturn(PaymentApplicationClassification.APPLIED);
        when(unappliedReceipt.getAmount())
                .thenReturn(BigDecimal.valueOf(10_000));
        when(unappliedReceipt.getApplicationClassification())
                .thenReturn(PaymentApplicationClassification.UNAPPLIED);
        when(paymentTransactionRepository
                .findByPaymentIdOrderByReceivedAtAscIdAsc(paymentId))
                .thenReturn(List.of(appliedReceipt, unappliedReceipt));
        when(driverBookingReadPolicy.snapshotForList(booking, DECISION_AT))
                .thenReturn(listSnapshot);
        when(bookingMapper.toBookingDetailResponse(
                eq(booking),
                eq(payment),
                any(BookingReadSnapshot.class)
        )).thenReturn(expected);

        BookingDetailResponse result = service.getMyBooking(BOOKING_ID);

        assertThat(result).isSameAs(expected);
        ArgumentCaptor<BookingReadSnapshot> snapshotCaptor =
                ArgumentCaptor.forClass(BookingReadSnapshot.class);
        verify(bookingMapper).toBookingDetailResponse(
                eq(booking),
                eq(payment),
                snapshotCaptor.capture()
        );
        BookingReadSnapshot snapshot = snapshotCaptor.getValue();
        assertThat(snapshot.evaluatedAt()).isEqualTo(DECISION_AT);
        assertThat(snapshot.stationAvailable()).isTrue();
        assertThat(snapshot.collectedAmount()).isEqualTo(136_000L);
        assertThat(snapshot.appliedToPackageAmount()).isEqualTo(126_000L);
        assertThat(snapshot.packageRefundedAmount()).isEqualTo(20_000L);
        assertThat(snapshot.excessAmount()).isZero();
        assertThat(snapshot.unallocatedAmount()).isEqualTo(10_000L);
        assertThat(snapshot.checkout().status())
                .isEqualTo(BookingDetailResponse.CheckoutState.READY);
        assertThat(snapshot.checkout().expiresAt())
                .isEqualTo(checkoutExpiresAt);
    }

    @Test
    void rejectsDetailAccessWhenBookingBelongsToAnotherDriver() {
        when(currentProfileProvider.requireProfile()).thenReturn(driver);
        when(applicationClock.instant()).thenReturn(DECISION_AT);
        when(bookingRepository.findByIdAndDriverId(BOOKING_ID, DRIVER_ID))
                .thenReturn(Optional.empty());
        when(bookingRepository.existsById(BOOKING_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.getMyBooking(BOOKING_ID))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> {
                            assertThat(exception.getErrorCode())
                                    .isEqualTo(BookingErrorCode.BOOKING_NOT_ACCESS);
                            assertThat(exception.getHttpStatus().value())
                                    .isEqualTo(403);
                        }
                );

        verifyNoInteractions(
                paymentRepository,
                paymentTransactionRepository,
                bookingMapper,
                driverBookingReadPolicy
        );
    }

    @Test
    void returnsNotFoundWhenBookingIdDoesNotExist() {
        when(currentProfileProvider.requireProfile()).thenReturn(driver);
        when(applicationClock.instant()).thenReturn(DECISION_AT);
        when(bookingRepository.findByIdAndDriverId(BOOKING_ID, DRIVER_ID))
                .thenReturn(Optional.empty());
        when(bookingRepository.existsById(BOOKING_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.getMyBooking(BOOKING_ID))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND)
                );

        verifyNoInteractions(
                paymentRepository,
                paymentTransactionRepository,
                bookingMapper,
                driverBookingReadPolicy
        );
    }

    @Test
    void getMyBookingStatsReturnsAggregatedStatsForCompletedAndCancelled() {
        when(currentProfileProvider.requireProfile()).thenReturn(driver);
        when(applicationClock.instant()).thenReturn(DECISION_AT);

        BookingCompletedSessionProjection session1 = mock(BookingCompletedSessionProjection.class);
        when(session1.getTotalAmount()).thenReturn(BigDecimal.valueOf(120_000));
        when(session1.getStartAt()).thenReturn(START_AT);
        when(session1.getEndAt()).thenReturn(START_AT.plusSeconds(3600)); // 60 mins

        BookingCompletedSessionProjection session2 = mock(BookingCompletedSessionProjection.class);
        when(session2.getTotalAmount()).thenReturn(BigDecimal.valueOf(60_000));
        when(session2.getStartAt()).thenReturn(START_AT.plusSeconds(7200));
        when(session2.getEndAt()).thenReturn(START_AT.plusSeconds(9000)); // 30 mins

        when(bookingRepository.findCompletedSessionsByDriverId(DRIVER_ID))
                .thenReturn(List.of(session1, session2));
        when(bookingRepository.countByDriverId(DRIVER_ID)).thenReturn(5L);
        when(bookingRepository.count(any(Specification.class))).thenReturn(2L);

        BookingStatsResponse stats = service.getMyBookingStats();

        assertThat(stats.totalSpending()).isEqualByComparingTo(BigDecimal.valueOf(180_000));
        assertThat(stats.totalChargingSessions()).isEqualTo(2L);
        assertThat(stats.totalBookings()).isEqualTo(5L);
        assertThat(stats.totalCompletedBookings()).isEqualTo(2L);
        assertThat(stats.totalCancelledBookings()).isEqualTo(2L);
        assertThat(stats.totalHours()).isEqualTo(1.5);
        assertThat(stats.spent()).isEqualByComparingTo(BigDecimal.valueOf(180_000));
        assertThat(stats.sessions()).isEqualTo(2L);
        assertThat(stats.hours()).isEqualTo(1.5);
    }

    @Test
    void getMyBookingStatsReturnsZerosWhenDriverHasNoBookings() {
        when(currentProfileProvider.requireProfile()).thenReturn(driver);
        when(applicationClock.instant()).thenReturn(DECISION_AT);

        when(bookingRepository.findCompletedSessionsByDriverId(DRIVER_ID))
                .thenReturn(List.of());
        when(bookingRepository.countByDriverId(DRIVER_ID)).thenReturn(0L);
        when(bookingRepository.count(any(Specification.class))).thenReturn(0L);

        BookingStatsResponse stats = service.getMyBookingStats();

        assertThat(stats.totalSpending()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(stats.totalChargingSessions()).isZero();
        assertThat(stats.totalBookings()).isZero();
        assertThat(stats.totalCompletedBookings()).isZero();
        assertThat(stats.totalCancelledBookings()).isZero();
        assertThat(stats.totalHours()).isZero();
        assertThat(stats.spent()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(stats.sessions()).isZero();
        assertThat(stats.hours()).isZero();
    }

    @Test
    void createCheckout_WhenInFlightLockRejects_ThrowsInProgressExceptionAndSkipsGateway() {
        UUID bookingId = UUID.randomUUID();
        UUID requestKey = UUID.randomUUID();

        when(currentProfileProvider.requireProfile()).thenReturn(driver);
        doThrow(new AppException(CommandErrorCode.IN_PROGRESS))
                .when(bookingCommandInFlightLock)
                .executeWithLock(eq(DRIVER_ID), eq(BookingCommandOperation.CREATE_CHECKOUT), eq(requestKey), any());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createCheckout(bookingId, requestKey))
                .isInstanceOf(AppException.class)
                .extracting(ex -> ((AppException) ex).getErrorCode())
                .isEqualTo(CommandErrorCode.IN_PROGRESS);

        verify(paymentGatewayRegistry, never()).createCheckout(any(), any());
        verify(bookingCheckoutPersistence, never()).prepareCheckout(any(), any(), any());
        verify(bookingCheckoutPersistence, never()).completeCheckout(any(), any(), any(), any(), any(), any());
    }

    @Test
    void createCheckout_WhenLockAcquired_ExecutesCheckoutFlowSuccessfully() {
        UUID bookingId = UUID.randomUUID();
        UUID requestKey = UUID.randomUUID();
        Payment payment = mock(Payment.class);
        CheckoutResponse expectedResponse = mock(CheckoutResponse.class);

        when(currentProfileProvider.requireProfile()).thenReturn(driver);
        when(applicationClock.instant()).thenReturn(DECISION_AT);
        when(bookingCommandRegistry.findReplay(eq(DRIVER_ID), eq(BookingCommandOperation.CREATE_CHECKOUT), eq(requestKey), anyString()))
                .thenReturn(Optional.empty());
        when(bookingCheckoutPersistence.prepareCheckout(bookingId, DRIVER_ID, DECISION_AT))
                .thenReturn(payment);
        when(payment.getProviderOrderRef()).thenReturn("SIM-ORDER-123");
        when(bookingCheckoutPersistence.completeCheckout(eq(bookingId), eq(DRIVER_ID), eq(requestKey), anyString(), isNull(), eq(DECISION_AT)))
                .thenReturn(expectedResponse);

        CheckoutResponse actual = service.createCheckout(bookingId, requestKey);

        assertThat(actual).isSameAs(expectedResponse);
        verify(bookingCommandInFlightLock).executeWithLock(eq(DRIVER_ID), eq(BookingCommandOperation.CREATE_CHECKOUT), eq(requestKey), any());
    }

    private CreateBookingRequest request(long amount, String pricingVersion, String policyVersion) {
        return new CreateBookingRequest(
                CONNECTOR_ID,
                START_AT,
                60,
                amount,
                pricingVersion,
                policyVersion,
                PaymentMethod.SIMULATOR
        );
    }
}
