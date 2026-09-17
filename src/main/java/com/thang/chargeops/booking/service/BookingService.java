package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.dto.filter.DriverBookingHistoryFilter;
import com.thang.chargeops.booking.dto.request.CreateBookingRequest;
import com.thang.chargeops.booking.dto.response.BookingDetailResponse;
import com.thang.chargeops.booking.dto.response.BookingStatsResponse;
import com.thang.chargeops.booking.dto.response.CreateBookingResponse;
import com.thang.chargeops.booking.dto.response.DriverBookingListItemResponse;
import com.thang.chargeops.booking.service.model.DriverBookingHistoryResult;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface BookingService {

    CreateBookingResponse createNewBooking(UUID requestKey, CreateBookingRequest request);

    Page<DriverBookingListItemResponse> getMyActiveBookings(int page, int size);

    DriverBookingHistoryResult getMyBookingHistory(
            DriverBookingHistoryFilter filter,
            int page,
            int size
    );

    BookingDetailResponse getMyBooking(UUID bookingId);

    BookingStatsResponse getMyBookingStats();

}
