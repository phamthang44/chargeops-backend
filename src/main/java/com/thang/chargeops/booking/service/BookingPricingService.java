package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.dto.request.PricePreviewRequest;
import com.thang.chargeops.booking.dto.response.PricePreviewResponse;

public interface BookingPricingService {

    PricePreviewResponse previewBookingPrice(PricePreviewRequest request);
}
