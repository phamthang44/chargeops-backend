package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.command.BookingCommand;
import com.thang.chargeops.booking.command.BookingCommandOperation;
import com.thang.chargeops.booking.command.BookingCommandRegistry;
import com.thang.chargeops.booking.config.BookingPolicyConfig;
import com.thang.chargeops.booking.dto.request.CreateBookingRequest;
import com.thang.chargeops.booking.dto.response.BookingPolicyResponse;
import com.thang.chargeops.booking.dto.response.CreateBookingResponse;
import com.thang.chargeops.booking.dto.response.PricePreviewResponse;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.history.BookingStatusActorType;
import com.thang.chargeops.booking.history.BookingStatusHistoryRecorder;
import com.thang.chargeops.booking.mapper.BookingMapper;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.impl.BookingServiceImpl;
import com.thang.chargeops.booking.service.model.HoldPreparationContext;
import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.repository.PaymentRepository;
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

import java.time.Instant;
import java.util.List;
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
    @Mock private BookingStatusHistoryRecorder bookingStatusHistoryRecorder;

    private BookingServiceImpl service;
    private UserProfile driver;
    private Connector connector;
    private ChargePoint chargePoint;
    private Station station;
    private BookingPolicyResponse policy;
    private PricePreviewResponse currentPrice;

    @BeforeEach
    void setUp() {
        service = new BookingServiceImpl(
                bookingRepository,
                bookingPricingService,
                bookingHoldCoordinator,
                currentProfileProvider,
                bookingCommandRegistry,
                bookingMapper,
                bookingPolicyConfig,
                paymentRepository,
                bookingStatusHistoryRecorder
        );

        driver = mock(UserProfile.class);
        connector = mock(Connector.class);
        chargePoint = mock(ChargePoint.class);
        station = mock(Station.class);
        policy = mock(BookingPolicyResponse.class);

        when(driver.getId()).thenReturn(DRIVER_ID);

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
