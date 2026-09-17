package com.thang.chargeops.booking.service.model;

import com.thang.chargeops.common.enums.BookingStatus;

import java.util.Objects;

/** Driver-facing state resolved at one request-time instant. */
public record BookingReadEvaluation(
        BookingStatus effectiveStatus,
        boolean stateReconciliationPending,
        CancellationReason cancellationReason,
        DriverBookingCapabilities capabilities
) {
    public enum CancellationReason {
        DRIVER_CANCELLED,
        NO_SHOW,
        STATION_FAILURE
    }

    public BookingReadEvaluation {
        Objects.requireNonNull(
                effectiveStatus,
                "effectiveStatus must not be null"
        );
        Objects.requireNonNull(
                capabilities,
                "capabilities must not be null"
        );
    }
}
