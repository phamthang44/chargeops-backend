package com.thang.chargeops.station.dto.station.response;

import java.util.List;
import java.util.UUID;

public record StationAmenitiesResponse(
        UUID stationId,
        List<String> amenities
) {
    public StationAmenitiesResponse {
        amenities = List.copyOf(amenities);
    }
}
