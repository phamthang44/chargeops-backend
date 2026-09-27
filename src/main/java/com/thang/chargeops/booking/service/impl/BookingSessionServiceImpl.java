package com.thang.chargeops.booking.service.impl;

import com.thang.chargeops.booking.command.BookingCommand;
import com.thang.chargeops.booking.command.BookingCommandOperation;
import com.thang.chargeops.booking.command.BookingCommandPayloadHasher;
import com.thang.chargeops.booking.command.BookingCommandRegistry;
import com.thang.chargeops.booking.dto.request.VersionRequest;
import com.thang.chargeops.booking.dto.response.BookingDetailResponse;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.history.BookingStatusActorType;
import com.thang.chargeops.booking.history.BookingStatusHistoryRecorder;
import com.thang.chargeops.booking.history.BookingStatusReason;
import com.thang.chargeops.booking.projection.BookingCheckInRouteProjection;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.BookingHoldCoordinator;
import com.thang.chargeops.booking.service.BookingSessionCompletionCoordinator;
import com.thang.chargeops.booking.service.BookingSessionService;
import com.thang.chargeops.booking.service.DriverBookingDetailAssembler;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import com.thang.chargeops.common.enums.ProvisioningStatus;
import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.common.enums.StationOperationalStatus;
import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.BookingErrorCode;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.repository.ConnectorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Implements the BKG-043 business session without claiming physical charger
 * telemetry. The Booking aggregate remains the session source of truth.
 */
@Service
@RequiredArgsConstructor
public class BookingSessionServiceImpl implements BookingSessionService {

    private final BookingRepository bookingRepository;
    private final ConnectorRepository connectorRepository;
    private final PaymentRepository paymentRepository;
    private final CurrentProfileProvider currentProfileProvider;
    private final BookingHoldCoordinator bookingHoldCoordinator;
    private final BookingCommandRegistry bookingCommandRegistry;
    private final BookingStatusHistoryRecorder bookingStatusHistoryRecorder;
    private final BookingSessionCompletionCoordinator completionCoordinator;
    private final DriverBookingDetailAssembler driverBookingDetailAssembler;
    private final Clock applicationClock;

    @Override
    @Transactional
    public BookingDetailResponse startCharging(
            UUID bookingId,
            UUID requestKey,
            VersionRequest request
    ) {
        requireCommandArguments(bookingId, requestKey, request);
        UserProfile driver = currentProfileProvider.requireProfile();
        String payloadHash = hashPayload(
                BookingCommandOperation.START_CHARGING,
                bookingId,
                request.expectedVersion()
        );

        Optional<UUID> replay = findReplay(
                driver.getId(),
                BookingCommandOperation.START_CHARGING,
                requestKey,
                payloadHash
        );
        if (replay.isPresent()) {
            return loadReplay(replay.get());
        }

        LockedSessionContext context = lockSession(
                driver,
                bookingId,
                BookingCommandOperation.START_CHARGING,
                requestKey,
                payloadHash
        );
        if (context.replayBookingId() != null) {
            return loadReplay(context.replayBookingId());
        }

        Instant decisionAt = applicationClock.instant();
        requireExpectedVersion(context.booking(), request.expectedVersion());
        requireCanStart(context.booking(), context.connector(), decisionAt);

        BookingCommand command = bookingCommandRegistry.recordSuccess(
                context.driver(),
                BookingCommandOperation.START_CHARGING,
                requestKey,
                payloadHash,
                context.booking(),
                decisionAt
        );
        bookingStatusHistoryRecorder.recordUserTransition(
                command,
                BookingStatusActorType.DRIVER,
                BookingStatusReason.CHARGING_STARTED,
                decisionAt,
                booking -> booking.startCharging(decisionAt)
        );
        bookingRepository.flush();
        return driverBookingDetailAssembler.assemble(
                context.booking(),
                context.payment(),
                decisionAt
        );
    }

    @Override
    @Transactional
    public BookingDetailResponse completeBooking(
            UUID bookingId,
            UUID requestKey,
            VersionRequest request
    ) {
        requireCommandArguments(bookingId, requestKey, request);
        UserProfile driver = currentProfileProvider.requireProfile();
        String payloadHash = hashPayload(
                BookingCommandOperation.COMPLETE_BOOKING,
                bookingId,
                request.expectedVersion()
        );

        Optional<UUID> replay = findReplay(
                driver.getId(),
                BookingCommandOperation.COMPLETE_BOOKING,
                requestKey,
                payloadHash
        );
        if (replay.isPresent()) {
            return loadReplay(replay.get());
        }

        LockedSessionContext context = lockSession(
                driver,
                bookingId,
                BookingCommandOperation.COMPLETE_BOOKING,
                requestKey,
                payloadHash
        );
        if (context.replayBookingId() != null) {
            return loadReplay(context.replayBookingId());
        }

        Instant decisionAt = applicationClock.instant();
        requireExpectedVersion(context.booking(), request.expectedVersion());
        requireCompletableState(context.booking());

        BookingCommand command = bookingCommandRegistry.recordSuccess(
                context.driver(),
                BookingCommandOperation.COMPLETE_BOOKING,
                requestKey,
                payloadHash,
                context.booking(),
                decisionAt
        );
        completionCoordinator.completeByDriver(
                command,
                context.booking(),
                context.connector(),
                decisionAt
        );

        return driverBookingDetailAssembler.assemble(
                context.booking(),
                context.payment(),
                decisionAt
        );
    }

    private LockedSessionContext lockSession(
            UserProfile driver,
            UUID bookingId,
            BookingCommandOperation operation,
            UUID requestKey,
            String payloadHash
    ) {
        UserProfile lockedDriver = bookingHoldCoordinator.lockDriver(driver.getId());
        Optional<UUID> lockedReplay = findReplay(
                lockedDriver.getId(),
                operation,
                requestKey,
                payloadHash
        );
        if (lockedReplay.isPresent()) {
            return LockedSessionContext.replay(lockedReplay.get());
        }

        BookingCheckInRouteProjection route = bookingRepository.findCheckInRouteById(bookingId)
                .orElseThrow(() -> new AppException(
                        CommonErrorCode.RESOURCE_NOT_FOUND,
                        "Booking not found: " + bookingId
                ));
        if (!Objects.equals(route.getDriverId(), lockedDriver.getId())) {
            throw new AppException(BookingErrorCode.BOOKING_NOT_ACCESS);
        }

        Connector connector = connectorRepository.findByIdWithLock(route.getConnectorId())
                .orElseThrow(() -> new AppException(
                        StationErrorCode.CONNECTOR_NOT_FOUND,
                        route.getConnectorId()
                ));
        Booking booking = bookingRepository
                .findByIdAndDriverIdWithLock(bookingId, lockedDriver.getId())
                .orElseThrow(() -> new AppException(
                        CommonErrorCode.RESOURCE_NOT_FOUND,
                        "Booking not found: " + bookingId
                ));
        Payment payment = paymentRepository.findByBookingIdWithLock(bookingId)
                .orElseThrow(() -> new AppException(
                        CommonErrorCode.RESOURCE_NOT_FOUND,
                        "Payment not found for booking: " + bookingId
                ));
        return LockedSessionContext.locked(lockedDriver, booking, connector, payment);
    }

    private Optional<UUID> findReplay(
            UUID driverId,
            BookingCommandOperation operation,
            UUID requestKey,
            String payloadHash
    ) {
        return bookingCommandRegistry.findReplay(
                driverId,
                operation,
                requestKey,
                payloadHash
        );
    }

    private BookingDetailResponse loadReplay(UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new AppException(
                        CommonErrorCode.RESOURCE_NOT_FOUND,
                        "Booking command exists but booking was not found: " + bookingId
                ));
        Payment payment = paymentRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new AppException(
                        CommonErrorCode.RESOURCE_NOT_FOUND,
                        "Booking exists but payment was not found: " + bookingId
                ));
        return driverBookingDetailAssembler.assemble(
                booking,
                payment,
                applicationClock.instant()
        );
    }

    private void requireExpectedVersion(Booking booking, Long expectedVersion) {
        if (!Objects.equals(booking.getVersion(), expectedVersion)) {
            throw new AppException(
                    BookingErrorCode.STATE_CONFLICT,
                    "Booking version changed"
            );
        }
    }

    private void requireCanStart(
            Booking booking,
            Connector connector,
            Instant decisionAt
    ) {
        if (booking.getStatus() != BookingStatus.CHECKED_IN) {
            throw new AppException(BookingErrorCode.STATE_CONFLICT);
        }
        if (booking.getEndAt() == null || !decisionAt.isBefore(booking.getEndAt())) {
            throw new AppException(
                    BookingErrorCode.STATE_CONFLICT,
                    "Booking session has reached endAt"
            );
        }
        if (!isServiceableActiveSessionHardware(connector)) {
            throw new AppException(BookingErrorCode.STATION_UNAVAILABLE);
        }
    }

    private void requireCompletableState(Booking booking) {
        if (booking.getStatus() != BookingStatus.CHECKED_IN
                && booking.getStatus() != BookingStatus.CHARGING) {
            throw new AppException(BookingErrorCode.STATE_CONFLICT);
        }
    }

    private boolean isServiceableActiveSessionHardware(Connector connector) {
        return connector.getRuntimeStatus() == RuntimeStatus.IN_USE
                && isOperationalWithoutRuntime(connector);
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

    private String hashPayload(
            BookingCommandOperation operation,
            UUID bookingId,
            Long expectedVersion
    ) {
        String operationName = switch (operation) {
            case START_CHARGING -> "start-charging-v1";
            case COMPLETE_BOOKING -> "complete-booking-v1";
            default -> throw new IllegalArgumentException(
                    "Unsupported session operation: " + operation
            );
        };
        return BookingCommandPayloadHasher.sha256(String.join("\n",
                operationName,
                canonicalField("bookingId", bookingId),
                canonicalField("expectedVersion", expectedVersion)
        ));
    }

    private String canonicalField(String name, Object value) {
        String text = Objects.requireNonNull(value, name + " must not be null").toString();
        return name + ":" + text.length() + ":" + text;
    }

    private void requireCommandArguments(
            UUID bookingId,
            UUID requestKey,
            VersionRequest request
    ) {
        Objects.requireNonNull(bookingId, "bookingId must not be null");
        Objects.requireNonNull(requestKey, "requestKey must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(request.expectedVersion(), "expectedVersion must not be null");
    }

    private record LockedSessionContext(
            UserProfile driver,
            Booking booking,
            Connector connector,
            Payment payment,
            UUID replayBookingId
    ) {
        private static LockedSessionContext replay(UUID bookingId) {
            return new LockedSessionContext(null, null, null, null, bookingId);
        }

        private static LockedSessionContext locked(
                UserProfile driver,
                Booking booking,
                Connector connector,
                Payment payment
        ) {
            return new LockedSessionContext(driver, booking, connector, payment, null);
        }
    }
}
