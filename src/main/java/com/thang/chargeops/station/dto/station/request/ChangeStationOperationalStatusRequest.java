package com.thang.chargeops.station.dto.station.request;

import com.thang.chargeops.common.enums.StationOperationalStatus;
import com.thang.chargeops.exception.errormessage.StationErrorMessage;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ChangeStationOperationalStatusRequest(
        @NotNull(message = StationErrorMessage.STATION_OPERATIONAL_STATUS_REQUIRED_KEY)
        StationOperationalStatus operationalStatus,

        @Size(max = 500, message = StationErrorMessage.STATUS_CHANGE_REASON_SIZE_KEY)
        String reason
) {
}
