package com.thang.chargeops.station.dto.chargepoint.request;

import com.thang.chargeops.common.enums.ConnectorType;
import com.thang.chargeops.exception.errormessage.StationErrorMessage;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ProvisionConnectorRequest(
        @Size(max = 50, message = StationErrorMessage.CONNECTOR_CODE_MAX_LENGTH_KEY)
        @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = StationErrorMessage.CONNECTOR_CODE_FORMAT_KEY)
        String connectorCode,

        @NotNull(message = StationErrorMessage.CONNECTOR_TYPE_REQUIRED_KEY)
        ConnectorType connectorType,

        @NotNull(message = StationErrorMessage.CONNECTOR_POWER_REQUIRED_KEY)
        @DecimalMin(value = "3.00", message = StationErrorMessage.CONNECTOR_POWER_MIN_KEY)
        @DecimalMax(value = "360.00", message = StationErrorMessage.CONNECTOR_POWER_MAX_KEY)
        BigDecimal powerKw
) {
}
