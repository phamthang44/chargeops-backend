package com.thang.chargeops.station.dto.license.request;

import com.thang.chargeops.common.enums.Plan;
import com.thang.chargeops.exception.errormessage.LicenseErrorMessage;
import jakarta.validation.constraints.NotNull;

public record IssueLicenseRequest(
        @NotNull(message = LicenseErrorMessage.PLAN_REQUIRED_KEY)
        Plan plan
) {
}
