package com.thang.chargeops.station.dto.license.response;

import com.thang.chargeops.common.enums.LicenseStatus;
import com.thang.chargeops.common.enums.Plan;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Builder
@Getter
@Setter
public class AdminLicenseDetailResponse {

    private UUID licenseId;
    private String licenseCode;
    private UUID stationId;
    private String stationCode;
    private String stationName;
    private UUID ownerId;
    private String ownerName;
    private String ownerEmail;
    private Plan plan;
    private BigDecimal feeAmount;
    private Instant startAt;
    private Instant expiresAt;
    private LicenseStatus status;
    private int daysLeft;
    private boolean isExpiringSoon;
    private Instant createdAt;
    private String recordedByName;

}
