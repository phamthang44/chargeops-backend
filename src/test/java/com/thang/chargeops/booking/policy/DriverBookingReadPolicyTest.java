package com.thang.chargeops.booking.policy;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.service.model.BookingReadEvaluation;
import com.thang.chargeops.booking.service.model.BookingReadSnapshot;
import com.thang.chargeops.booking.service.model.DriverBookingCapabilities;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import com.thang.chargeops.common.enums.ProvisioningStatus;
import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.common.enums.StationOperationalStatus;
import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.Station;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DriverBookingReadPolicyTest {

    private static final Instant START_AT =
            Instant.parse("2026-09-17T03:00:00Z");
    private static final Instant END_AT =
            Instant.parse("2026-09-17T04:00:00Z");

    private final DriverBookingReadPolicy policy =
            new DriverBookingReadPolicy();

    @Test
    void expiresPendingExactlyAtHoldDeadline() {
        Instant deadline = Instant.parse("2026-09-17T02:10:00Z");
        Booking booking = booking(BookingStatus.PENDING);
        when(booking.getExpiresAt()).thenReturn(deadline);

        BookingReadEvaluation evaluation = policy.evaluate(
                booking,
                BookingReadSnapshot.forList(deadline, true)
        );

        assertThat(evaluation.effectiveStatus())
                .isEqualTo(BookingStatus.EXPIRED);
        assertThat(evaluation.stateReconciliationPending()).isTrue();
        assertThat(evaluation.capabilities().canCancel()).isFalse();
    }

    @Test
    void enablesRefundAndCheckInInsideConfirmedWindows() {
        Instant evaluatedAt = Instant.parse("2026-09-17T03:05:00Z");
        Booking booking = booking(BookingStatus.CONFIRMED);
        when(booking.getFreeCancellationDeadline())
                .thenReturn(Instant.parse("2026-09-17T03:10:00Z"));
        when(booking.getCheckInDeadline())
                .thenReturn(Instant.parse("2026-09-17T03:45:00Z"));
        BookingReadSnapshot snapshot = BookingReadSnapshot.builder()
                .evaluatedAt(evaluatedAt)
                .stationAvailable(true)
                .canReportIssue(true)
                .packageRefundedAmount(26_000L)
                .build();

        BookingReadEvaluation evaluation = policy.evaluate(booking, snapshot);

        assertThat(evaluation.effectiveStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(evaluation.capabilities().canCancel()).isTrue();
        assertThat(evaluation.capabilities().refundableAmount())
                .isEqualTo(100_000L);
        assertThat(evaluation.capabilities().cancellationReason()).isEqualTo(
                DriverBookingCapabilities.CancellationReason.WITHIN_GRACE
        );
        assertThat(evaluation.capabilities().canCheckIn()).isTrue();
    }

    @Test
    void resolvesNoShowExactlyAtCheckInDeadline() {
        Instant deadline = Instant.parse("2026-09-17T03:45:00Z");
        Booking booking = booking(BookingStatus.CONFIRMED);
        when(booking.getCheckInDeadline()).thenReturn(deadline);

        BookingReadEvaluation evaluation = policy.evaluate(
                booking,
                BookingReadSnapshot.forList(deadline, true)
        );

        assertThat(evaluation.effectiveStatus())
                .isEqualTo(BookingStatus.CANCELLED);
        assertThat(evaluation.cancellationReason())
                .isEqualTo(BookingReadEvaluation.CancellationReason.NO_SHOW);
        assertThat(evaluation.capabilities().checkInReason()).isEqualTo(
                DriverBookingCapabilities.CheckInReason.WINDOW_CLOSED
        );
    }

    @Test
    void disablesCheckInWhenChargePointProvisioningIsNotActive() {
        Instant evaluatedAt = Instant.parse("2026-09-17T03:05:00Z");
        Booking booking = booking(BookingStatus.CONFIRMED);
        when(booking.getCheckInDeadline())
                .thenReturn(Instant.parse("2026-09-17T03:45:00Z"));

        Station station = mock(Station.class);
        when(station.getStatus()).thenReturn(StationStatus.ACTIVE);
        when(station.getOperationalStatus())
                .thenReturn(StationOperationalStatus.OPERATING);

        ChargePoint chargePoint = mock(ChargePoint.class);
        when(chargePoint.getStation()).thenReturn(station);
        when(chargePoint.getProvisioningStatus())
                .thenReturn(ProvisioningStatus.SUSPENDED);
        when(chargePoint.getOperationalChargePointStatus())
                .thenReturn(OperationalChargePointStatus.AVAILABLE);

        Connector connector = mock(Connector.class);
        when(connector.getChargePoint()).thenReturn(chargePoint);
        when(connector.getRuntimeStatus()).thenReturn(RuntimeStatus.AVAILABLE);
        when(booking.getConnector()).thenReturn(connector);

        BookingReadSnapshot snapshot = policy.snapshotForList(
                booking,
                evaluatedAt
        );
        BookingReadEvaluation evaluation = policy.evaluate(booking, snapshot);

        assertThat(snapshot.stationAvailable()).isFalse();
        assertThat(evaluation.capabilities().canCheckIn()).isFalse();
        assertThat(evaluation.capabilities().checkInReason()).isEqualTo(
                DriverBookingCapabilities.CheckInReason.STATION_UNAVAILABLE
        );
    }

    @Test
    void enablesStartForCheckedInBookingWhoseConnectorIsInUse() {
        Instant evaluatedAt = Instant.parse("2026-09-17T03:10:00Z");
        Booking booking = booking(BookingStatus.CHECKED_IN);

        Station station = mock(Station.class);
        when(station.getStatus()).thenReturn(StationStatus.ACTIVE);
        when(station.getOperationalStatus())
                .thenReturn(StationOperationalStatus.OPERATING);

        ChargePoint chargePoint = mock(ChargePoint.class);
        when(chargePoint.getStation()).thenReturn(station);
        when(chargePoint.getProvisioningStatus())
                .thenReturn(ProvisioningStatus.ACTIVE);
        when(chargePoint.getOperationalChargePointStatus())
                .thenReturn(OperationalChargePointStatus.AVAILABLE);

        Connector connector = mock(Connector.class);
        when(connector.getChargePoint()).thenReturn(chargePoint);
        when(connector.getRuntimeStatus()).thenReturn(RuntimeStatus.IN_USE);
        when(booking.getConnector()).thenReturn(connector);

        BookingReadSnapshot snapshot = policy.snapshotForList(booking, evaluatedAt);
        BookingReadEvaluation evaluation = policy.evaluate(booking, snapshot);

        assertThat(snapshot.stationAvailable()).isTrue();
        assertThat(evaluation.capabilities().canStartCharging()).isTrue();
        assertThat(evaluation.capabilities().canComplete()).isTrue();
    }

    @Test
    void doesNotTreatInUseConnectorAsAvailableForConfirmedCheckIn() {
        Instant evaluatedAt = Instant.parse("2026-09-17T03:10:00Z");
        Booking booking = booking(BookingStatus.CONFIRMED);
        when(booking.getCheckInDeadline())
                .thenReturn(Instant.parse("2026-09-17T03:45:00Z"));

        Station station = mock(Station.class);
        when(station.getStatus()).thenReturn(StationStatus.ACTIVE);
        when(station.getOperationalStatus())
                .thenReturn(StationOperationalStatus.OPERATING);
        ChargePoint chargePoint = mock(ChargePoint.class);
        when(chargePoint.getStation()).thenReturn(station);
        when(chargePoint.getProvisioningStatus())
                .thenReturn(ProvisioningStatus.ACTIVE);
        when(chargePoint.getOperationalChargePointStatus())
                .thenReturn(OperationalChargePointStatus.AVAILABLE);
        Connector connector = mock(Connector.class);
        when(connector.getChargePoint()).thenReturn(chargePoint);
        when(connector.getRuntimeStatus()).thenReturn(RuntimeStatus.IN_USE);
        when(booking.getConnector()).thenReturn(connector);

        BookingReadSnapshot snapshot = policy.snapshotForList(booking, evaluatedAt);
        BookingReadEvaluation evaluation = policy.evaluate(booking, snapshot);

        assertThat(snapshot.stationAvailable()).isFalse();
        assertThat(evaluation.capabilities().canCheckIn()).isFalse();
    }

    @Test
    void disablesStartExactlyAtBookingEndEvenWhenHardwareIsServiceable() {
        Booking booking = booking(BookingStatus.CHECKED_IN);

        BookingReadEvaluation evaluation = policy.evaluate(
                booking,
                BookingReadSnapshot.forList(END_AT, true)
        );

        assertThat(evaluation.capabilities().canStartCharging()).isFalse();
        assertThat(evaluation.capabilities().canComplete()).isTrue();
    }

    @Test
    void failsClosedWhenConfirmedBookingHasNoStartAt() {
        Booking booking = booking(BookingStatus.CONFIRMED);
        when(booking.getStartAt()).thenReturn(null);
        when(booking.getCheckInDeadline())
                .thenReturn(Instant.parse("2026-09-17T03:45:00Z"));

        BookingReadEvaluation evaluation = policy.evaluate(
                booking,
                BookingReadSnapshot.forList(
                        Instant.parse("2026-09-17T03:05:00Z"),
                        true
                )
        );

        assertThat(evaluation.capabilities().canCheckIn()).isFalse();
        assertThat(evaluation.capabilities().checkInReason()).isEqualTo(
                DriverBookingCapabilities.CheckInReason.WRONG_STATE
        );
    }

    @Test
    void failsClosedWhenConfirmedBookingHasNoDeadlineSnapshot() {
        Booking booking = booking(BookingStatus.CONFIRMED);
        when(booking.getCheckInDeadline()).thenReturn(null);

        BookingReadEvaluation evaluation = policy.evaluate(
                booking,
                BookingReadSnapshot.forList(
                        Instant.parse("2026-09-17T03:05:00Z"),
                        true
                )
        );

        assertThat(evaluation.capabilities().canCheckIn()).isFalse();
        assertThat(evaluation.capabilities().checkInReason()).isEqualTo(
                DriverBookingCapabilities.CheckInReason.WRONG_STATE
        );
    }

    private Booking booking(BookingStatus status) {
        Booking booking = mock(Booking.class);
        when(booking.getStatus()).thenReturn(status);
        when(booking.getStartAt()).thenReturn(START_AT);
        when(booking.getEndAt()).thenReturn(END_AT);
        when(booking.getTotalAmount())
                .thenReturn(new BigDecimal("126000.00"));
        return booking;
    }
}
