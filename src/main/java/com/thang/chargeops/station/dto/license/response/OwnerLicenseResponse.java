package com.thang.chargeops.station.dto.license.response;

import com.thang.chargeops.common.enums.LicenseStatus;
import com.thang.chargeops.common.enums.Plan;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder
@Getter
@Setter
public class OwnerLicenseResponse {

    private UUID id;
    private UUID stationId;
    private String stationName;
    private String stationCode;
    private UUID ownerId;
    private Plan plan;
    private BigDecimal feeAmount;
    private Instant startAt;
    private Instant expiresAt;
    private LicenseStatus status;
    private int daysLeft;
    private boolean isExpiringSoon;

    private List<OwnerLicenseHistoryResponse> histories;
}

