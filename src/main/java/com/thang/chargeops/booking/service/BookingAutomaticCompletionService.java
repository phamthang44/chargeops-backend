package com.thang.chargeops.booking.service;

import java.time.Instant;
import java.util.UUID;

public interface BookingAutomaticCompletionService {
    boolean completeIfDue(UUID bookingId, UUID connectorId, Instant decisionAt);
}
