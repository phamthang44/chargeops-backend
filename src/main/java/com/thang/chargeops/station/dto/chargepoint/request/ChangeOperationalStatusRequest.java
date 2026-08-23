package com.thang.chargeops.station.dto.chargepoint.request;

import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import com.thang.chargeops.exception.errormessage.StationErrorMessage;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChangeOperationalStatusRequest(
        @NotNull(message = StationErrorMessage.CHARGE_POINT_OPERATIONAL_STATUS_REQUIRED_KEY)
        OperationalChargePointStatus operationalStatus,

        @Size(max = 500, message = StationErrorMessage.STATUS_CHANGE_REASON_SIZE_KEY)
        String reason
) {
}
