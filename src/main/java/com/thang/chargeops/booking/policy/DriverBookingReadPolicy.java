package com.thang.chargeops.booking.policy;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.service.model.BookingReadEvaluation;
import com.thang.chargeops.booking.service.model.BookingReadSnapshot;
import com.thang.chargeops.booking.service.model.DriverBookingCapabilities;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.common.enums.StationOperationalStatus;
import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.Station;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * Evaluates Driver-facing effective state and capabilities for booking reads.
 * All time/status comparisons live here; response mapping does not own them.
 */
@Component
public class DriverBookingReadPolicy {

    public BookingReadSnapshot snapshotForList(
            Booking booking,
            Instant evaluatedAt
    ) {
        Objects.requireNonNull(booking, "booking must not be null");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
        return BookingReadSnapshot.forList(
                evaluatedAt,
                isStationAvailable(booking)
        );
    }

    public BookingReadEvaluation evaluate(
            Booking booking,
            BookingReadSnapshot snapshot
    ) {
        Objects.requireNonNull(booking, "booking must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");

        BookingStatus effectiveStatus = effectiveStatus(
                booking,
                snapshot.evaluatedAt()
        );
        return new BookingReadEvaluation(
                effectiveStatus,
                effectiveStatus != booking.getStatus(),
                cancellationReason(booking, effectiveStatus),
                capabilities(booking, effectiveStatus, snapshot)
        );
    }

    BookingStatus effectiveStatus(Booking booking, Instant evaluatedAt) {
        if (booking.getStatus() == BookingStatus.PENDING
                && booking.getExpiresAt() != null
                && !evaluatedAt.isBefore(booking.getExpiresAt())) {
            return BookingStatus.EXPIRED;
        }
        if (booking.getStatus() == BookingStatus.CONFIRMED
                && booking.getCheckInDeadline() != null
                && !evaluatedAt.isBefore(booking.getCheckInDeadline())) {
            return BookingStatus.CANCELLED;
        }
        return booking.getStatus();
    }

    private DriverBookingCapabilities capabilities(
            Booking booking,
            BookingStatus effectiveStatus,
            BookingReadSnapshot snapshot
    ) {
        boolean canCancel = effectiveStatus == BookingStatus.PENDING
                || effectiveStatus == BookingStatus.CONFIRMED;
        boolean withinGrace = effectiveStatus == BookingStatus.CONFIRMED
                && booking.getFreeCancellationDeadline() != null
                && snapshot.evaluatedAt().isBefore(
                        booking.getFreeCancellationDeadline()
                );
        long refundableAmount = withinGrace
                ? Math.max(
                        0,
                        booking.getTotalAmount().longValueExact()
                                - snapshot.packageRefundedAmount()
                )
                : 0;
        DriverBookingCapabilities.CheckInReason checkInReason =
                checkInReason(booking, effectiveStatus, snapshot);

        return new DriverBookingCapabilities(
                canCancel,
                refundableAmount,
                cancellationCapabilityReason(effectiveStatus, withinGrace),
                checkInReason
                        == DriverBookingCapabilities.CheckInReason.AVAILABLE,
                checkInReason,
                effectiveStatus == BookingStatus.CHECKED_IN
                        && snapshot.stationAvailable(),
                effectiveStatus == BookingStatus.CHECKED_IN
                        || effectiveStatus == BookingStatus.CHARGING,
                snapshot.canReportIssue()
        );
    }

    private DriverBookingCapabilities.CancellationReason
    cancellationCapabilityReason(
            BookingStatus status,
            boolean withinGrace
    ) {
        if (status == BookingStatus.PENDING) {
            return DriverBookingCapabilities.CancellationReason.UNPAID;
        }
        if (status == BookingStatus.CONFIRMED) {
            return withinGrace
                    ? DriverBookingCapabilities.CancellationReason.WITHIN_GRACE
                    : DriverBookingCapabilities.CancellationReason.GRACE_ENDED;
        }
        return DriverBookingCapabilities.CancellationReason.NOT_CANCELLABLE;
    }

    private DriverBookingCapabilities.CheckInReason checkInReason(
            Booking booking,
            BookingStatus effectiveStatus,
            BookingReadSnapshot snapshot
    ) {
        if (booking.getStatus() == BookingStatus.CONFIRMED
                && effectiveStatus == BookingStatus.CANCELLED
                && booking.getCheckInDeadline() != null
                && !snapshot.evaluatedAt().isBefore(
                        booking.getCheckInDeadline()
                )) {
            return DriverBookingCapabilities.CheckInReason.WINDOW_CLOSED;
        }
        if (effectiveStatus != BookingStatus.CONFIRMED) {
            return DriverBookingCapabilities.CheckInReason.WRONG_STATE;
        }
        if (snapshot.evaluatedAt().isBefore(booking.getStartAt())) {
            return DriverBookingCapabilities.CheckInReason.TOO_EARLY;
        }
        if (booking.getCheckInDeadline() != null
                && !snapshot.evaluatedAt().isBefore(
                        booking.getCheckInDeadline()
                )) {
            return DriverBookingCapabilities.CheckInReason.WINDOW_CLOSED;
        }
        if (!snapshot.stationAvailable()) {
            return DriverBookingCapabilities.CheckInReason.STATION_UNAVAILABLE;
        }
        return DriverBookingCapabilities.CheckInReason.AVAILABLE;
    }

    private BookingReadEvaluation.CancellationReason cancellationReason(
            Booking booking,
            BookingStatus effectiveStatus
    ) {
        if (booking.getStatus() == BookingStatus.CONFIRMED
                && effectiveStatus == BookingStatus.CANCELLED) {
            return BookingReadEvaluation.CancellationReason.NO_SHOW;
        }
        if (booking.getCancellationReason() == null
                || booking.getCancellationReason().isBlank()) {
            return null;
        }
        try {
            return BookingReadEvaluation.CancellationReason.valueOf(
                    booking.getCancellationReason()
                            .trim()
                            .toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean isStationAvailable(Booking booking) {
        Connector connector = booking.getConnector();
        if (connector == null || connector.getChargePoint() == null) {
            return false;
        }
        ChargePoint chargePoint = connector.getChargePoint();
        Station station = chargePoint.getStation();
        return station != null
                && station.getStatus() == StationStatus.ACTIVE
                && station.getOperationalStatus()
                == StationOperationalStatus.OPERATING
                && chargePoint.getOperationalChargePointStatus()
                == OperationalChargePointStatus.AVAILABLE
                && connector.getRuntimeStatus() == RuntimeStatus.AVAILABLE;
    }
}
