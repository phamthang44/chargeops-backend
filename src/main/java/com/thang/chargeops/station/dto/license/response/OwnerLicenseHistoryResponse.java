package com.thang.chargeops.station.dto.license.response;

import com.thang.chargeops.common.enums.LicenseStatus;
import com.thang.chargeops.common.enums.Plan;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@Builder
public class OwnerLicenseHistoryResponse {

    private String licenseCode;
    private Plan plan;
    private Instant startAt;
    private Instant expiresAt;
    private BigDecimal feeAmount;
    private LicenseStatus status;

}
