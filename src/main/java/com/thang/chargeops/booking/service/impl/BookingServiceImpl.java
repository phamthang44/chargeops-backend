package com.thang.chargeops.booking.service.impl;

import com.thang.chargeops.booking.dto.request.CreateBookingRequest;
import com.thang.chargeops.booking.dto.response.CreateBookingResponse;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.BookingService;
import com.thang.chargeops.station.policy.StationBusinessEligibilityPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class BookingServiceImpl implements BookingService {

    private final BookingRepository bookingRepository;
    private final StationBusinessEligibilityPolicy stationBusinessEligibilityPolicy;


    @Transactional
    @Override
    public CreateBookingResponse createNewBooking(UUID connectorId, CreateBookingRequest request) {



        return null;
    }
}
