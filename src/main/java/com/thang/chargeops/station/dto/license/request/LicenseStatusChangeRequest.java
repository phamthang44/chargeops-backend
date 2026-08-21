package com.thang.chargeops.station.dto.license.request;

import com.thang.chargeops.exception.errormessage.LicenseErrorMessage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LicenseStatusChangeRequest(
        @NotBlank(message = LicenseErrorMessage.STATUS_CHANGE_REASON_REQUIRED_KEY)
        @Size(min = 5, max = 500, message = LicenseErrorMessage.STATUS_CHANGE_REASON_SIZE_KEY)
        String reason
) {
}
