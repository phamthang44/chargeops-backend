package com.thang.chargeops.station.dto.station.request;

import com.thang.chargeops.exception.errormessage.ApprovalErrorMessage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class RejectStationRequest {

    @NotBlank(message = ApprovalErrorMessage.REJECTION_REASON_VALIDATION_REQUIRED_KEY)
    @Size(max = 500, message = ApprovalErrorMessage.REJECTION_REASON_MAX_LENGTH_KEY)
    private String reason;

}
