package com.thang.chargeops.station.dto.chargepoint.request;

import com.thang.chargeops.common.enums.ConnectorType;
import com.thang.chargeops.exception.errormessage.StationErrorMessage;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record ConnectorProvisioningGroupRequest(
        @NotNull(message = StationErrorMessage.CONNECTOR_TYPE_REQUIRED_KEY)
        ConnectorType connectorType,

        @NotNull(message = StationErrorMessage.CONNECTOR_POWER_REQUIRED_KEY)
        @DecimalMin(value = "3.00", message = StationErrorMessage.CONNECTOR_POWER_MIN_KEY)
        @DecimalMax(value = "360.00", message = StationErrorMessage.CONNECTOR_POWER_MAX_KEY)
        BigDecimal powerKw,

        @NotNull(message = StationErrorMessage.CONNECTOR_QUANTITY_REQUIRED_KEY)
        @Min(value = 1, message = StationErrorMessage.CONNECTOR_QUANTITY_MIN_KEY)
        @Max(value = 8, message = StationErrorMessage.CHARGE_POINT_CONNECTOR_COUNT_MAX_KEY)
        Integer quantity
) {
}
