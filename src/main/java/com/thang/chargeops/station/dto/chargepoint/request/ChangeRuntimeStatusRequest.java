package com.thang.chargeops.station.dto.chargepoint.request;

import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.exception.errormessage.StationErrorMessage;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChangeRuntimeStatusRequest(
        @NotNull(message = StationErrorMessage.CONNECTOR_RUNTIME_STATUS_REQUIRED_KEY)
        RuntimeStatus runtimeStatus,

        @Size(max = 500, message = StationErrorMessage.STATUS_CHANGE_REASON_SIZE_KEY)
        String reason
) {
}
