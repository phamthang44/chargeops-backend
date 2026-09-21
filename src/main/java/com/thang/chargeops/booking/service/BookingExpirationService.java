package com.thang.chargeops.booking.service;

import java.time.Instant;
import java.util.UUID;

public interface BookingExpirationService {

    boolean expireIfDue(UUID bookingId, UUID connectorId, Instant now);
}
