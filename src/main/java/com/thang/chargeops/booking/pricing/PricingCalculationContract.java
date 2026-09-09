package com.thang.chargeops.booking.pricing;

import java.util.List;

public interface PricingCalculationContract {

    PricePreview calculate(PriceBasis basis, List<PriceSegment> segments);

}
