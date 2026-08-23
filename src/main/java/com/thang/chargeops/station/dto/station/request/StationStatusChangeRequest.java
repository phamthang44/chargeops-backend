package com.thang.chargeops.station.dto.station.request;


import com.thang.chargeops.exception.errormessage.StationErrorMessage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StationStatusChangeRequest(
        @NotBlank(message = StationErrorMessage.STATUS_CHANGE_REASON_REQUIRED_KEY)
        @Size(max = 500, message = StationErrorMessage.STATUS_CHANGE_REASON_SIZE_KEY)
        String reason
) {
}
