package com.thang.chargeops.booking.policy;

import com.thang.chargeops.booking.policy.model.CancellationRefundContext;
import com.thang.chargeops.booking.policy.model.CancellationPolicySummary;
import com.thang.chargeops.booking.policy.model.CancellationRefundDecision;

import java.time.Instant;

public interface BookingCancellationPolicy {

    CancellationRefundDecision calculateRefund(
            CancellationRefundContext booking,
            Instant cancelledAt,
            boolean noShow
    );

    CancellationPolicySummary getSummary();
}
