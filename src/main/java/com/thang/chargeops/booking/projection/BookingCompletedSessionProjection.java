package com.thang.chargeops.booking.projection;

import java.math.BigDecimal;
import java.time.Instant;

public interface BookingCompletedSessionProjection {

    BigDecimal getTotalAmount();

    Instant getStartAt();

    Instant getEndAt();
}
