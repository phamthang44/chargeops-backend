package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.dto.request.CancelBookingRequest;
import com.thang.chargeops.booking.dto.response.BookingDetailResponse;

import java.util.UUID;

public interface BookingCancellationService {

    /**
     * Executes voluntary Driver booking cancellation with explicit consent validation.
     * Enforces strict pessimistic locking, idempotency replay, and atomic refund obligation.
     */
    BookingDetailResponse cancelBooking(UUID bookingId, UUID requestKey, CancelBookingRequest request);
}
