package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.dto.filter.DriverBookingHistoryFilter;
import com.thang.chargeops.booking.dto.request.CreateBookingRequest;
import com.thang.chargeops.booking.dto.request.ConfirmCheckInRequest;
import com.thang.chargeops.booking.dto.request.ResolveCheckInRequest;
import com.thang.chargeops.booking.dto.response.*;
import com.thang.chargeops.booking.service.model.DriverBookingHistoryResult;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface BookingService {

    CreateBookingResponse createNewBooking(UUID requestKey, CreateBookingRequest request);

    CheckoutResponse createCheckout(UUID bookingId, UUID requestKey);

    Page<DriverBookingListItemResponse> getMyActiveBookings(int page, int size);

    DriverBookingHistoryResult getMyBookingHistory(
            DriverBookingHistoryFilter filter,
            int page,
            int size
    );

    BookingDetailResponse getMyBooking(UUID bookingId);

    BookingStatsResponse getMyBookingStats();

    ResolveCheckInResponse resolveCheckIn(ResolveCheckInRequest request);

    BookingDetailResponse confirmCheckIn(
            UUID bookingId,
            UUID requestKey,
            ConfirmCheckInRequest request
    );

}
