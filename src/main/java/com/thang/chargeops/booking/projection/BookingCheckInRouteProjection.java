package com.thang.chargeops.booking.projection;

import java.util.UUID;

/**
 * Scalar route used before entity locking so Hibernate does not preload Booking
 * ahead of the required Connector -> Booking lock order.
 */
public interface BookingCheckInRouteProjection {
    UUID getBookingId();
    UUID getDriverId();
    UUID getConnectorId();
}
