package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.dto.filter.StationOperationalBookingFilter;
import com.thang.chargeops.booking.dto.response.OperationalBookingResponse;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface StationOperationalBookingService {

    Page<OperationalBookingResponse> getOperationalBookings(
            UUID stationId, StationOperationalBookingFilter filter, int page, int size);

    OperationalBookingResponse getOperationalBooking(UUID stationId, UUID bookingId);
}
