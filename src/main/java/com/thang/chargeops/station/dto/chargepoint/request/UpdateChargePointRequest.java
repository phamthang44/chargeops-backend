package com.thang.chargeops.station.dto.chargepoint.request;

import com.thang.chargeops.exception.errormessage.StationErrorMessage;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateChargePointRequest(
        @Size(max = 100, message = StationErrorMessage.CHARGE_POINT_NAME_MAX_LENGTH_KEY)
        @Pattern(regexp = "(?s).*\\S.*", message = StationErrorMessage.CHARGE_POINT_NAME_REQUIRED_KEY)
        String name,

        @Size(max = 100, message = StationErrorMessage.CHARGE_POINT_ZONE_MAX_LENGTH_KEY)
        String zoneLabel
) {
    @AssertTrue(message = StationErrorMessage.CHARGE_POINT_UPDATE_REQUIRED_KEY)
    public boolean isAnyFieldPresent() {
        return name != null || zoneLabel != null;
    }
}
