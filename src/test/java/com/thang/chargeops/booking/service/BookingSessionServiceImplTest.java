package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.command.BookingCommand;
import com.thang.chargeops.booking.command.BookingCommandOperation;
import com.thang.chargeops.booking.command.BookingCommandRegistry;
import com.thang.chargeops.booking.dto.request.VersionRequest;
import com.thang.chargeops.booking.dto.response.BookingDetailResponse;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.history.BookingStatusActorType;
import com.thang.chargeops.booking.history.BookingStatusHistoryRecorder;
import com.thang.chargeops.booking.history.BookingStatusReason;
import com.thang.chargeops.booking.projection.BookingCheckInRouteProjection;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.impl.BookingSessionServiceImpl;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import com.thang.chargeops.common.enums.ProvisioningStatus;
import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.common.enums.StationOperationalStatus;
import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.BookingErrorCode;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.repository.ConnectorRepository;
import com.thang.chargeops.station.service.EquipmentStatusHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingSessionServiceImplTest {

    private static final UUID DRIVER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID BOOKING_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID CONNECTOR_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID REQUEST_KEY = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant NOW = Instant.parse("2026-09-17T03:30:00Z");

    @Mock private BookingRepository bookingRepository;
    @Mock private ConnectorRepository connectorRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private CurrentProfileProvider currentProfileProvider;
    @Mock private BookingHoldCoordinator bookingHoldCoordinator;
    @Mock private BookingCommandRegistry bookingCommandRegistry;
    @Mock private BookingStatusHistoryRecorder bookingStatusHistoryRecorder;
    @Mock private EquipmentStatusHistoryService equipmentStatusHistoryService;
    @Mock private DriverBookingDetailAssembler driverBookingDetailAssembler;
    @Mock private UserProfile driver;
    @Mock private Booking booking;
    @Mock private Connector connector;
    @Mock private ChargePoint chargePoint;
    @Mock private Station station;
    @Mock private Payment payment;
    @Mock private BookingCommand command;
    @Mock private BookingCheckInRouteProjection route;

    private BookingSessionServiceImpl service;
    private BookingSessionCompletionCoordinator completionCoordinator;

    @BeforeEach
    void setUp() {
        completionCoordinator = new BookingSessionCompletionCoordinator(
                bookingRepository,
                bookingStatusHistoryRecorder,
                equipmentStatusHistoryService
        );
        service = new BookingSessionServiceImpl(
                bookingRepository,
                connectorRepository,
                paymentRepository,
                currentProfileProvider,
                bookingHoldCoordinator,
                bookingCommandRegistry,
                bookingStatusHistoryRecorder,
                completionCoordinator,
                driverBookingDetailAssembler,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        lenient().when(driver.getId()).thenReturn(DRIVER_ID);
        lenient().when(booking.getId()).thenReturn(BOOKING_ID);
    }

    @Test
    void startChargingCommitsOneBusinessTransitionWithoutChangingConnectorRuntime() {
        stubLockedContext(BookingStatus.CHECKED_IN, 3L);
        stubOperationalHardware();
        when(booking.getEndAt()).thenReturn(NOW.plusSeconds(1800));
        BookingDetailResponse response = mock(BookingDetailResponse.class);
        when(driverBookingDetailAssembler.assemble(booking, payment, NOW))
                .thenReturn(response);
        executeRecordedTransition();

        BookingDetailResponse result = service.startCharging(
                BOOKING_ID,
                REQUEST_KEY,
                new VersionRequest(3L)
        );

        assertThat(result).isSameAs(response);
        InOrder order = inOrder(
                bookingHoldCoordinator,
                connectorRepository,
                bookingRepository,
                paymentRepository,
                bookingCommandRegistry,
                bookingStatusHistoryRecorder
        );
        order.verify(bookingHoldCoordinator).lockDriver(DRIVER_ID);
        order.verify(connectorRepository).findByIdWithLock(CONNECTOR_ID);
        order.verify(bookingRepository).findByIdAndDriverIdWithLock(BOOKING_ID, DRIVER_ID);
        order.verify(paymentRepository).findByBookingIdWithLock(BOOKING_ID);
        order.verify(bookingCommandRegistry).recordSuccess(
                eq(driver),
                eq(BookingCommandOperation.START_CHARGING),
                eq(REQUEST_KEY),
                anyString(),
                eq(booking),
                eq(NOW)
        );
        order.verify(bookingStatusHistoryRecorder).recordUserTransition(
                eq(command),
                eq(BookingStatusActorType.DRIVER),
                eq(BookingStatusReason.CHARGING_STARTED),
                eq(NOW),
                any()
        );
        verify(booking).startCharging(NOW);
        verifyNoInteractions(equipmentStatusHistoryService);
    }

    @Test
    void startChargingAtEndIsRejectedBeforeMutation() {
        stubLockedContext(BookingStatus.CHECKED_IN, 3L);
        when(booking.getEndAt()).thenReturn(NOW);

        assertThatThrownBy(() -> service.startCharging(
                BOOKING_ID,
                REQUEST_KEY,
                new VersionRequest(3L)
        ))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(BookingErrorCode.STATE_CONFLICT);

        verify(bookingCommandRegistry, never()).recordSuccess(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(bookingStatusHistoryRecorder, equipmentStatusHistoryService);
    }

    @Test
    void completeChargingReleasesConnectorWhenNoBlockerRemains() {
        stubLockedContext(BookingStatus.CHARGING, 4L);
        stubOperationalHardware();
        when(bookingRepository.existsByConnectorIdAndStatusIn(eq(CONNECTOR_ID), anyCollection()))
                .thenReturn(false);
        BookingDetailResponse response = mock(BookingDetailResponse.class);
        when(driverBookingDetailAssembler.assemble(booking, payment, NOW))
                .thenReturn(response);
        executeRecordedTransition();

        BookingDetailResponse result = service.completeBooking(
                BOOKING_ID,
                REQUEST_KEY,
                new VersionRequest(4L)
        );

        assertThat(result).isSameAs(response);
        verify(booking).complete(NOW);
        verify(bookingStatusHistoryRecorder).recordUserTransition(
                eq(command),
                eq(BookingStatusActorType.DRIVER),
                eq(BookingStatusReason.SESSION_COMPLETED),
                eq(NOW),
                any()
        );
        verify(equipmentStatusHistoryService).transitionConnectorRuntimeAsSystem(
                connector,
                RuntimeStatus.AVAILABLE,
                NOW,
                "BOOKING_COMPLETE:" + BOOKING_ID
        );
        verify(bookingRepository, times(2)).flush();
    }

    @Test
    void completeFromCheckedInDoesNotClearMaintenanceState() {
        stubLockedContext(BookingStatus.CHECKED_IN, 4L);
        when(connector.getRuntimeStatus()).thenReturn(RuntimeStatus.IN_USE);
        when(connector.getChargePoint()).thenReturn(chargePoint);
        when(chargePoint.getStation()).thenReturn(station);
        when(station.getStatus()).thenReturn(StationStatus.ACTIVE);
        when(station.getOperationalStatus()).thenReturn(StationOperationalStatus.OPERATING);
        when(chargePoint.getProvisioningStatus()).thenReturn(ProvisioningStatus.ACTIVE);
        when(chargePoint.getOperationalChargePointStatus())
                .thenReturn(OperationalChargePointStatus.MAINTENANCE);
        executeRecordedTransition();

        service.completeBooking(
                BOOKING_ID,
                REQUEST_KEY,
                new VersionRequest(4L)
        );

        verify(booking).complete(NOW);
        verifyNoInteractions(equipmentStatusHistoryService);
        verify(bookingRepository, never())
                .existsByConnectorIdAndStatusIn(any(), anyCollection());
    }

    @Test
    void completeKeepsConnectorInUseWhenAnotherActiveSessionExists() {
        stubLockedContext(BookingStatus.CHARGING, 4L);
        stubOperationalHardware();
        when(bookingRepository.existsByConnectorIdAndStatusIn(eq(CONNECTOR_ID), anyCollection()))
                .thenReturn(true);
        executeRecordedTransition();

        service.completeBooking(
                BOOKING_ID,
                REQUEST_KEY,
                new VersionRequest(4L)
        );

        verifyNoInteractions(equipmentStatusHistoryService);
    }

    @Test
    void committedStartReplayDoesNotAcquireLocksOrMutateAgain() {
        when(currentProfileProvider.requireProfile()).thenReturn(driver);
        when(bookingCommandRegistry.findReplay(
                eq(DRIVER_ID),
                eq(BookingCommandOperation.START_CHARGING),
                eq(REQUEST_KEY),
                anyString()
        )).thenReturn(Optional.of(BOOKING_ID));
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(paymentRepository.findByBookingId(BOOKING_ID)).thenReturn(Optional.of(payment));
        BookingDetailResponse response = mock(BookingDetailResponse.class);
        when(driverBookingDetailAssembler.assemble(booking, payment, NOW))
                .thenReturn(response);

        assertThat(service.startCharging(
                BOOKING_ID,
                REQUEST_KEY,
                new VersionRequest(3L)
        )).isSameAs(response);

        verifyNoInteractions(bookingHoldCoordinator, connectorRepository, bookingStatusHistoryRecorder);
        verify(booking, never()).startCharging(any());
    }

    @Test
    void staleVersionDoesNotRecordCommandOrTransition() {
        stubLockedContext(BookingStatus.CHECKED_IN, 5L);

        assertThatThrownBy(() -> service.startCharging(
                BOOKING_ID,
                REQUEST_KEY,
                new VersionRequest(4L)
        ))
                .isInstanceOf(AppException.class)
                .extracting("errorCode")
                .isEqualTo(BookingErrorCode.STATE_CONFLICT);

        verify(bookingCommandRegistry, never()).recordSuccess(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(bookingStatusHistoryRecorder, equipmentStatusHistoryService);
    }

    private void stubLockedContext(BookingStatus status, long version) {
        when(currentProfileProvider.requireProfile()).thenReturn(driver);
        when(bookingHoldCoordinator.lockDriver(DRIVER_ID)).thenReturn(driver);
        when(bookingRepository.findCheckInRouteById(BOOKING_ID)).thenReturn(Optional.of(route));
        when(route.getDriverId()).thenReturn(DRIVER_ID);
        when(route.getConnectorId()).thenReturn(CONNECTOR_ID);
        when(connectorRepository.findByIdWithLock(CONNECTOR_ID)).thenReturn(Optional.of(connector));
        when(bookingRepository.findByIdAndDriverIdWithLock(BOOKING_ID, DRIVER_ID))
                .thenReturn(Optional.of(booking));
        when(paymentRepository.findByBookingIdWithLock(BOOKING_ID)).thenReturn(Optional.of(payment));
        lenient().when(booking.getStatus()).thenReturn(status);
        when(booking.getVersion()).thenReturn(version);
        lenient().when(bookingCommandRegistry.recordSuccess(
                eq(driver),
                any(BookingCommandOperation.class),
                eq(REQUEST_KEY),
                anyString(),
                eq(booking),
                eq(NOW)
        )).thenReturn(command);
    }

    private void stubOperationalHardware() {
        lenient().when(connector.getId()).thenReturn(CONNECTOR_ID);
        when(connector.getRuntimeStatus()).thenReturn(RuntimeStatus.IN_USE);
        when(connector.getChargePoint()).thenReturn(chargePoint);
        when(chargePoint.getStation()).thenReturn(station);
        when(station.getStatus()).thenReturn(StationStatus.ACTIVE);
        when(station.getOperationalStatus()).thenReturn(StationOperationalStatus.OPERATING);
        when(chargePoint.getProvisioningStatus()).thenReturn(ProvisioningStatus.ACTIVE);
        when(chargePoint.getOperationalChargePointStatus())
                .thenReturn(OperationalChargePointStatus.AVAILABLE);
    }

    @SuppressWarnings("unchecked")
    private void executeRecordedTransition() {
        doAnswer(invocation -> {
            Consumer<Booking> transition = invocation.getArgument(4);
            transition.accept(booking);
            return null;
        }).when(bookingStatusHistoryRecorder).recordUserTransition(
                any(BookingCommand.class),
                eq(BookingStatusActorType.DRIVER),
                any(BookingStatusReason.class),
                eq(NOW),
                any(Consumer.class)
        );
    }
}
