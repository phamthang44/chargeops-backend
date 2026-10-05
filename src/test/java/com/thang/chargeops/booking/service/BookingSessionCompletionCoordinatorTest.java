package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.history.BookingStatusHistoryRecorder;
import com.thang.chargeops.booking.history.BookingStatusReason;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import com.thang.chargeops.common.enums.ProvisioningStatus;
import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.common.enums.StationOperationalStatus;
import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.service.EquipmentStatusHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BookingSessionCompletionCoordinatorTest {

    private static final UUID BOOKING_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID CONNECTOR_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final Instant NOW = Instant.parse("2026-09-27T11:00:00Z");

    @Mock private BookingRepository bookingRepository;
    @Mock private BookingStatusHistoryRecorder historyRecorder;
    @Mock private EquipmentStatusHistoryService equipmentStatusHistoryService;
    @Mock private Booking booking;
    @Mock private Connector connector;
    @Mock private ChargePoint chargePoint;
    @Mock private Station station;

    private BookingSessionCompletionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new BookingSessionCompletionCoordinator(
                bookingRepository,
                historyRecorder,
                equipmentStatusHistoryService
        );
        lenient().when(booking.getId()).thenReturn(BOOKING_ID);
        lenient().when(connector.getId()).thenReturn(CONNECTOR_ID);
    }

    @Test
    void systemCompletionRecordsHistoryBeforeSafelyReleasingConnector() {
        stubOperationalConnector();
        when(bookingRepository.existsByConnectorIdAndStatusIn(eq(CONNECTOR_ID), anyCollection()))
                .thenReturn(false);
        executeSystemTransition();

        coordinator.completeBySystem(booking, connector, NOW);

        verify(booking).complete(NOW);
        InOrder order = inOrder(historyRecorder, bookingRepository, equipmentStatusHistoryService);
        order.verify(historyRecorder).recordSystemTransition(
                eq(booking),
                eq(BookingStatusReason.SESSION_COMPLETED),
                eq(NOW),
                any()
        );
        order.verify(bookingRepository).flush();
        order.verify(equipmentStatusHistoryService).transitionConnectorRuntimeAsSystem(
                connector,
                RuntimeStatus.AVAILABLE,
                NOW,
                "BOOKING_COMPLETE:" + BOOKING_ID
        );
        order.verify(bookingRepository).flush();
    }

    @Test
    void systemCompletionDoesNotClearMaintenanceState() {
        when(connector.getRuntimeStatus()).thenReturn(RuntimeStatus.IN_USE);
        when(connector.getChargePoint()).thenReturn(chargePoint);
        when(chargePoint.getStation()).thenReturn(station);
        when(station.getStatus()).thenReturn(StationStatus.ACTIVE);
        when(station.getOperationalStatus()).thenReturn(StationOperationalStatus.OPERATING);
        when(chargePoint.getProvisioningStatus()).thenReturn(ProvisioningStatus.ACTIVE);
        when(chargePoint.getOperationalChargePointStatus())
                .thenReturn(OperationalChargePointStatus.MAINTENANCE);
        executeSystemTransition();

        coordinator.completeBySystem(booking, connector, NOW);

        verify(booking).complete(NOW);
        verifyNoInteractions(equipmentStatusHistoryService);
        verify(bookingRepository, never())
                .existsByConnectorIdAndStatusIn(any(), anyCollection());
    }

    @Test
    void systemCompletionKeepsConnectorInUseForAnotherActiveSession() {
        stubOperationalConnector();
        when(bookingRepository.existsByConnectorIdAndStatusIn(eq(CONNECTOR_ID), anyCollection()))
                .thenReturn(true);
        executeSystemTransition();

        coordinator.completeBySystem(booking, connector, NOW);

        verifyNoInteractions(equipmentStatusHistoryService);
    }

    @SuppressWarnings("unchecked")
    private void executeSystemTransition() {
        doAnswer(invocation -> {
            Consumer<Booking> transition = invocation.getArgument(3);
            transition.accept(booking);
            return null;
        }).when(historyRecorder).recordSystemTransition(
                eq(booking),
                eq(BookingStatusReason.SESSION_COMPLETED),
                eq(NOW),
                any(Consumer.class)
        );
    }

    private void stubOperationalConnector() {
        when(connector.getRuntimeStatus()).thenReturn(RuntimeStatus.IN_USE);
        when(connector.getChargePoint()).thenReturn(chargePoint);
        when(chargePoint.getStation()).thenReturn(station);
        when(station.getStatus()).thenReturn(StationStatus.ACTIVE);
        when(station.getOperationalStatus()).thenReturn(StationOperationalStatus.OPERATING);
        when(chargePoint.getProvisioningStatus()).thenReturn(ProvisioningStatus.ACTIVE);
        when(chargePoint.getOperationalChargePointStatus())
                .thenReturn(OperationalChargePointStatus.AVAILABLE);
    }
}
