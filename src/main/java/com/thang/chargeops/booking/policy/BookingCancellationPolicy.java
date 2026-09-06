package com.thang.chargeops.booking.policy;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.policy.model.CancellationPolicySummary;
import com.thang.chargeops.booking.policy.model.CancellationRefundDecision;

import java.time.Instant;

public interface BookingCancellationPolicy {

    CancellationRefundDecision calculateRefund(
            Booking booking,
            Instant cancelledAt,
            boolean noShow
    );

    CancellationPolicySummary getSummary();
}
