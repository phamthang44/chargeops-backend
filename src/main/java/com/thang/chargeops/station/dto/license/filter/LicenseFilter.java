package com.thang.chargeops.station.dto.license.filter;

import com.thang.chargeops.common.enums.LicenseStatus;

import java.util.UUID;

public record LicenseFilter(
        String search,
        String licenseCode,
        LicenseStatus status,
        String ownerName,
        String ownerEmail,
        String stationName,
        String stationCode,
        UUID stationId
) {
}
