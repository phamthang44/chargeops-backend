package com.thang.chargeops.station.dto;

import com.thang.chargeops.exception.errormessage.ErrorMessage;
import com.thang.chargeops.exception.errormessage.StationErrorMessage;
import com.thang.chargeops.station.dto.station.request.RegisterStationRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class RegisterStationRequestValidationTest {

    @Test
    void resolvesStationValidationKeysToEnglishFallbackMessages() {
        assertThat(ErrorMessage.defaultMessage(StationErrorMessage.STATION_NAME_REQUIRED_KEY))
                .isEqualTo("Station name is required");
        assertThat(ErrorMessage.defaultMessage(StationErrorMessage.STATION_WARD_CODE_INVALID_KEY))
                .isEqualTo("Station ward code must contain digits only");
    }

    @Test
    void usesStationSpecificKeysForEveryInvalidField() {
        RegisterStationRequest request = new RegisterStationRequest(
                "",
                "",
                "x".repeat(501),
                "invalid",
                "invalid",
                new BigDecimal("91"),
                new BigDecimal("181"),
                "0123456789",
                0
        );

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            Map<String, Set<String>> messagesByField = validator.validate(request).stream()
                    .collect(Collectors.groupingBy(
                            violation -> violation.getPropertyPath().toString(),
                            Collectors.mapping(violation -> violation.getMessage(), Collectors.toSet())
                    ));

            assertThat(messagesByField.get("name"))
                    .contains(StationErrorMessage.STATION_NAME_REQUIRED_KEY);
            assertThat(messagesByField.get("addressLine"))
                    .contains(StationErrorMessage.STATION_ADDRESS_REQUIRED_KEY);
            assertThat(messagesByField.get("description"))
                    .contains(StationErrorMessage.STATION_DESCRIPTION_MAX_LENGTH_KEY);
            assertThat(messagesByField.get("provinceCode"))
                    .contains(StationErrorMessage.STATION_PROVINCE_CODE_INVALID_KEY);
            assertThat(messagesByField.get("wardCode"))
                    .contains(StationErrorMessage.STATION_WARD_CODE_INVALID_KEY);
            assertThat(messagesByField.get("latitude"))
                    .contains(StationErrorMessage.STATION_LATITUDE_INVALID_KEY);
            assertThat(messagesByField.get("longitude"))
                    .contains(StationErrorMessage.STATION_LONGITUDE_INVALID_KEY);
            assertThat(messagesByField.get("contactPhone"))
                    .contains(StationErrorMessage.STATION_CONTACT_PHONE_INVALID_KEY);
            assertThat(messagesByField.get("plannedChargePointCount"))
                    .contains(StationErrorMessage.STATION_PLANNED_CHARGE_POINT_COUNT_KEY);
            assertThat(messagesByField.values().stream().flatMap(Set::stream))
                    .noneMatch(message -> message.startsWith("jakarta.validation.constraints."));
        }
    }
}
