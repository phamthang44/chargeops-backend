package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.dto.filter.OwnerActiveBookingFilter;
import com.thang.chargeops.booking.dto.filter.OwnerBookingFilter;
import com.thang.chargeops.booking.dto.response.*;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface OwnerBookingService {
    Page<OwnerBookingListItemResponse> getOwnerBookings(OwnerBookingFilter filter, int page, int size);
    BookingSummaryResponse getOwnerBookingSummary(OwnerBookingFilter filter);
    Page<OperationalBookingResponse> getOwnerActiveBookingsFor(OwnerActiveBookingFilter filter, int page, int size);
    OwnerBookingDetailResponse getOwnerBooking(UUID bookingId);
}
