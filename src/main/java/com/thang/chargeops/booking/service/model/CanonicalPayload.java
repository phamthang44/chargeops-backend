package com.thang.chargeops.booking.service.model;

import com.thang.chargeops.common.enums.PaymentMethod;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record CanonicalPayload(
        UUID connectorId,
        Instant startAt,
        int durationMin,
        long acceptedTotalAmount,
        String acceptedPricingVersion,
        String acceptedPolicyVersion,
        PaymentMethod paymentMethod
) {
}
