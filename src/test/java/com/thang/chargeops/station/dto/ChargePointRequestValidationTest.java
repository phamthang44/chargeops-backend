package com.thang.chargeops.station.dto;

import com.thang.chargeops.exception.errormessage.ErrorMessage;
import com.thang.chargeops.exception.errormessage.StationErrorMessage;
import com.thang.chargeops.station.dto.chargepoint.request.ActivateChargePointRequest;
import com.thang.chargeops.station.dto.chargepoint.request.ChangeOperationalStatusRequest;
import com.thang.chargeops.station.dto.chargepoint.request.ChangeRuntimeStatusRequest;
import com.thang.chargeops.station.dto.chargepoint.request.ConnectorProvisioningGroupRequest;
import com.thang.chargeops.station.dto.chargepoint.request.ProvisionChargePointRequest;
import com.thang.chargeops.station.dto.chargepoint.request.ProvisionConnectorRequest;
import com.thang.chargeops.station.dto.chargepoint.request.UpdateChargePointRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ChargePointRequestValidationTest {

    @Test
    void resolvesNewValidationKeysToEnglishFallbackMessages() {
        assertThat(ErrorMessage.defaultMessage(StationErrorMessage.CHARGE_POINT_CONNECTOR_GROUPS_REQUIRED_KEY))
                .isEqualTo("At least one connector group is required");
        assertThat(ErrorMessage.defaultMessage(StationErrorMessage.CONNECTOR_POWER_MAX_KEY))
                .isEqualTo("Connector power cannot exceed 360.0 kW");
        assertThat(ErrorMessage.defaultMessage(StationErrorMessage.PAGE_SIZE_MAX_KEY))
                .isEqualTo("Page size cannot exceed 100");
    }

    @Test
    void validatesProvisioningAndOperationDtosWithStableKeys() {
        ProvisionChargePointRequest chargePoint = new ProvisionChargePointRequest(
                "invalid code",
                " ",
                "z".repeat(101),
                List.of(new ConnectorProvisioningGroupRequest(
                        null, new BigDecimal("2.00"), 0
                ))
        );
        ProvisionConnectorRequest connector = new ProvisionConnectorRequest(
                "invalid code",
                null,
                null
        );
        UpdateChargePointRequest emptyUpdate = new UpdateChargePointRequest(null, null);
        ActivateChargePointRequest activation = new ActivateChargePointRequest(0);
        ChangeOperationalStatusRequest operational = new ChangeOperationalStatusRequest(null, null);
        ChangeRuntimeStatusRequest runtime = new ChangeRuntimeStatusRequest(null, null);

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            Set<String> messages = Set.of(
                    chargePoint,
                    connector,
                    emptyUpdate,
                    activation,
                    operational,
                    runtime
            ).stream()
                    .flatMap(request -> validator.validate(request).stream())
                    .map(violation -> violation.getMessage())
                    .collect(Collectors.toSet());

            assertThat(messages).contains(
                    StationErrorMessage.CHARGE_POINT_CODE_FORMAT_KEY,
                    StationErrorMessage.CHARGE_POINT_ZONE_MAX_LENGTH_KEY,
                    StationErrorMessage.CONNECTOR_CODE_FORMAT_KEY,
                    StationErrorMessage.CONNECTOR_TYPE_REQUIRED_KEY,
                    StationErrorMessage.CONNECTOR_POWER_REQUIRED_KEY,
                    StationErrorMessage.CONNECTOR_POWER_MIN_KEY,
                    StationErrorMessage.CONNECTOR_QUANTITY_MIN_KEY,
                    StationErrorMessage.CHARGE_POINT_UPDATE_REQUIRED_KEY,
                    StationErrorMessage.CHARGE_POINT_EXPECTED_CONNECTOR_COUNT_MIN_KEY,
                    StationErrorMessage.CHARGE_POINT_OPERATIONAL_STATUS_REQUIRED_KEY,
                    StationErrorMessage.CONNECTOR_RUNTIME_STATUS_REQUIRED_KEY
            );
        }
    }
}
