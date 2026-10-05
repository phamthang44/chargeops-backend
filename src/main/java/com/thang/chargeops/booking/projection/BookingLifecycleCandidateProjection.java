package com.thang.chargeops.booking.projection;

import java.util.UUID;

/** Lightweight route used by lifecycle polling without loading Booking graphs. */
public interface BookingLifecycleCandidateProjection {
    UUID getBookingId();
    UUID getConnectorId();
}
