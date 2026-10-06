package com.thang.chargeops.station.dto.station.response;

import com.thang.chargeops.common.enums.StationStatus;
import java.math.BigDecimal;
import java.util.UUID;

public record OwnerStationRegistrationResponse(
        UUID id, String stationCode, String name, String addressLine,
        String description, String provinceCode, String wardCode,
        String provinceName, String wardName, BigDecimal latitude, BigDecimal longitude,
        String contactPhone, int plannedChargePointCount, StationStatus status, long version
) {}
