package com.thang.chargeops.booking.service;

import java.time.Instant;
import java.util.UUID;

public interface BookingNoShowService {
    boolean markNoShowIfDue(UUID bookingId, UUID connectorId, Instant decisionAt);
}
