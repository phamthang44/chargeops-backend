package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.dto.request.VersionRequest;
import com.thang.chargeops.booking.dto.response.BookingDetailResponse;

import java.util.UUID;

/** Application boundary for the simulated Booking usage session. */
public interface BookingSessionService {

    BookingDetailResponse startCharging(
            UUID bookingId,
            UUID requestKey,
            VersionRequest request
    );

    BookingDetailResponse completeBooking(
            UUID bookingId,
            UUID requestKey,
            VersionRequest request
    );
}
