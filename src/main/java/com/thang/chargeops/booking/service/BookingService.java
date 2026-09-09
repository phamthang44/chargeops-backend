package com.thang.chargeops.booking.service;

import com.thang.chargeops.booking.dto.request.CreateBookingRequest;
import com.thang.chargeops.booking.dto.response.CreateBookingResponse;

import java.util.UUID;

public interface BookingService {

    CreateBookingResponse createNewBooking(UUID connectorId, CreateBookingRequest request);


}
