package com.thang.chargeops.booking.service;

import com.thang.chargeops.station.dto.station.filter.StationAvailabilityQuery;
import com.thang.chargeops.station.dto.station.response.StationAvailabilityResponse;

import java.util.UUID;

public interface StationAvailabilityService {

    StationAvailabilityResponse getAvailability(
            UUID stationId,
            StationAvailabilityQuery query
    );
}
