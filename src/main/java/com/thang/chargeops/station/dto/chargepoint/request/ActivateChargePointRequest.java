package com.thang.chargeops.station.dto.chargepoint.request;

import com.thang.chargeops.exception.errormessage.StationErrorMessage;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Explicit connector-inventory confirmation for charge-point activation.
 */
public record ActivateChargePointRequest(
        @NotNull(message = StationErrorMessage.CHARGE_POINT_EXPECTED_CONNECTOR_COUNT_REQUIRED_KEY)
        @Min(value = 1, message = StationErrorMessage.CHARGE_POINT_EXPECTED_CONNECTOR_COUNT_MIN_KEY)
        Integer expectedConnectorCount
) {
}
