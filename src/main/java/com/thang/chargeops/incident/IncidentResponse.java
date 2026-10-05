package com.thang.chargeops.incident;

import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.RuntimeStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Operational evidence only. Financial decisions and execution outcomes have separate APIs. */
public record IncidentResponse(UUID incidentId, UUID stationId, UUID connectorId,
        ConnectorIncident.Status status, long version, long connectorVersion, RuntimeStatus runtimeStatus,
        String reason, Instant occurredAt, Instant reportedAt, Instant recoveredAt, String recoveryReason,
        List<UUID> affectedBookingIds, String handlingState, List<AffectedBooking> affectedBookings) {
    public record AffectedBooking(UUID bookingId, BookingStatus snapshotStatus, long snapshotVersion,
            BookingStatus currentStatus, long currentVersion, IncidentBooking.State safetyState,
            Instant resolvedAt, String resolutionReason) { }
}
