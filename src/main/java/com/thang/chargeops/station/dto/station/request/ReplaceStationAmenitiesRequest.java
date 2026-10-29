package com.thang.chargeops.station.dto.station.request;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ReplaceStationAmenitiesRequest(
        @NotNull
        List<@NotNull String> amenities
) {
}
