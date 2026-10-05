package com.thang.chargeops.booking.projection;

import java.util.UUID;

public interface BookingExpirationCandidateProjection {
    UUID getBookingId();
    UUID getConnectorId();
}
