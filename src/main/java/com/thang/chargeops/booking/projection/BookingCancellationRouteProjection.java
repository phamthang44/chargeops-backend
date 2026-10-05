package com.thang.chargeops.booking.projection;

import java.util.UUID;

/**
 * Read-only scalar projection used to resolve route ownership and lock hierarchy
 * without loading the Booking entity into Hibernate L1 session.
 */
public interface BookingCancellationRouteProjection {
    UUID getBookingId();
    UUID getDriverId();
    UUID getConnectorId();
}
