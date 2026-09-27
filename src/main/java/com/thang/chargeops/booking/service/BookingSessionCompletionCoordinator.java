package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.command.BookingCommand;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.history.BookingStatusActorType;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;

/** Shared completion effects for the Driver command and the endAt worker. */
@Service
@RequiredArgsConstructor
public class BookingSessionCompletionCoordinator {

    private static final EnumSet<BookingStatus> ACTIVE_SESSION_STATUSES =
            EnumSet.of(BookingStatus.CHECKED_IN, BookingStatus.CHARGING);

    private final BookingRepository bookingRepository;
    private final BookingStatusHistoryRecorder bookingStatusHistoryRecorder;
    private final EquipmentStatusHistoryService equipmentStatusHistoryService;

    @Transactional(propagation = Propagation.MANDATORY)
    public void completeByDriver(
            BookingCommand command,
            Booking booking,
            Connector connector,
            Instant decisionAt
    ) {
        bookingStatusHistoryRecorder.recordUserTransition(
                command,
                BookingStatusActorType.DRIVER,
                BookingStatusReason.SESSION_COMPLETED,
                decisionAt,
                current -> current.complete(decisionAt)
        );
        finishCompletion(booking, connector, decisionAt);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void completeBySystem(
            Booking booking,
            Connector connector,
            Instant decisionAt
    ) {
        bookingStatusHistoryRecorder.recordSystemTransition(
                booking,
                BookingStatusReason.SESSION_COMPLETED,
                decisionAt,
                current -> current.complete(decisionAt)
        );
        finishCompletion(booking, connector, decisionAt);
    }

    private void finishCompletion(
            Booking booking,
            Connector connector,
            Instant decisionAt
    ) {
        bookingRepository.flush();
        releaseConnectorWhenSafe(connector, booking.getId(), decisionAt);
        bookingRepository.flush();
    }

    private void releaseConnectorWhenSafe(
            Connector connector,
            UUID bookingId,
            Instant decisionAt
    ) {
        if (connector.getRuntimeStatus() != RuntimeStatus.IN_USE
                || !isOperationalWithoutRuntime(connector)
                || bookingRepository.existsByConnectorIdAndStatusIn(
                        connector.getId(),
                        ACTIVE_SESSION_STATUSES
                )) {
            return;
        }
        equipmentStatusHistoryService.transitionConnectorRuntimeAsSystem(
                connector,
                RuntimeStatus.AVAILABLE,
                decisionAt,
                "BOOKING_COMPLETE:" + bookingId
        );
    }

    private boolean isOperationalWithoutRuntime(Connector connector) {
        ChargePoint chargePoint = connector.getChargePoint();
        if (chargePoint == null) {
            return false;
        }
        Station station = chargePoint.getStation();
        return station != null
                && station.getStatus() == StationStatus.ACTIVE
                && station.getOperationalStatus() == StationOperationalStatus.OPERATING
                && chargePoint.getProvisioningStatus() == ProvisioningStatus.ACTIVE
                && chargePoint.getOperationalChargePointStatus()
                == OperationalChargePointStatus.AVAILABLE;
    }
}
