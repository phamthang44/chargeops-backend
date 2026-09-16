package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.dto.request.PricePreviewRequest;
import com.thang.chargeops.booking.dto.response.PricePreviewResponse;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.entity.Connector;

import java.time.Instant;

public interface BookingPricingService {

    PricePreviewResponse previewBookingPrice(PricePreviewRequest request);

    PricePreviewResponse repriceUnderLock(
            UserProfile driver,
            Connector lockedConnector,
            Instant startAt,
            int durationMin,
            Instant decisionAt
    );
}
