package com.thang.chargeops.booking.pricing;

import java.util.List;

public record PricePreview(
        long totalAmount,
        List<PriceLine> priceLines,
        PriceBasis pricingBasis
) {
}
