package com.thang.chargeops.booking.dto.response;

import com.thang.chargeops.booking.pricing.PriceBasis;
import com.thang.chargeops.booking.pricing.PriceLine;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PricePreviewResponse(
        String pricingVersion,
        UUID connectorId,
        Instant startAt,
        Instant endAt,
        int durationMin,
        String currency,
        long totalAmount,
        List<PriceLine> priceLines,
        PriceBasis pricingBasis,
        BookingPolicyResponse policy,
        List<String> overlapWarnings
) {
}
