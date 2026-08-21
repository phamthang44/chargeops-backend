package com.thang.chargeops.station.dto.license.response;

import com.thang.chargeops.common.enums.LicenseStatus;
import com.thang.chargeops.common.enums.Plan;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
public class AdminLicenseListItemResponse {

    private UUID id;
    private String licenseCode;
    private UUID stationId;
    private String stationCode;
    private String stationName;
    private String ownerName;
    private Plan plan;
    private Instant startAt;
    private Instant expiresAt;
    private LicenseStatus status;
    private int daysLeft;
    private boolean expiringSoon;
    private BigDecimal feeAmount;

}
