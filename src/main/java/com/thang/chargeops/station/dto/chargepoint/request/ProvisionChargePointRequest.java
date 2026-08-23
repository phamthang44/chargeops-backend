package com.thang.chargeops.station.dto.chargepoint.request;

import com.thang.chargeops.exception.errormessage.StationErrorMessage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ProvisionChargePointRequest(
        @Size(max = 80, message = StationErrorMessage.CHARGE_POINT_CODE_MAX_LENGTH_KEY)
        @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = StationErrorMessage.CHARGE_POINT_CODE_FORMAT_KEY)
        String chargePointCode,

        @Size(max = 100, message = StationErrorMessage.CHARGE_POINT_NAME_MAX_LENGTH_KEY)
        String name,

        @Size(max = 100, message = StationErrorMessage.CHARGE_POINT_ZONE_MAX_LENGTH_KEY)
        String zoneLabel,

        @NotEmpty(message = StationErrorMessage.CHARGE_POINT_CONNECTOR_GROUPS_REQUIRED_KEY)
        @Size(max = 8, message = StationErrorMessage.CHARGE_POINT_CONNECTOR_COUNT_MAX_KEY)
        List<@Valid ConnectorProvisioningGroupRequest> connectorGroups
) {
    @AssertTrue(message = StationErrorMessage.CHARGE_POINT_CONNECTOR_COUNT_MAX_KEY)
    public boolean isTotalConnectorCountValid() {
        return connectorGroups == null
                || connectorGroups.stream()
                .mapToInt(group -> group.quantity() == null ? 0 : group.quantity())
                .sum() <= 8;
    }
}
