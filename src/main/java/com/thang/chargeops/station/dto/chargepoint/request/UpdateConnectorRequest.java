package com.thang.chargeops.station.dto.chargepoint.request;

import com.thang.chargeops.common.enums.ConnectorType;
import com.thang.chargeops.exception.errormessage.StationErrorMessage;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

public record UpdateConnectorRequest(
        ConnectorType connectorType,

        @DecimalMin(value = "3.00", message = StationErrorMessage.CONNECTOR_POWER_MIN_KEY)
        @DecimalMax(value = "360.00", message = StationErrorMessage.CONNECTOR_POWER_MAX_KEY)
        BigDecimal powerKw
) {
    @AssertTrue(message = StationErrorMessage.CONNECTOR_UPDATE_REQUIRED_KEY)
    public boolean isAnyFieldPresent() {
        return connectorType != null || powerKw != null;
    }
}
