package com.thang.chargeops.booking.projection;

import java.time.Instant;

public interface BookingTimeRangeProjection {

    Instant getStartAt();

    Instant getEndAt();
}
