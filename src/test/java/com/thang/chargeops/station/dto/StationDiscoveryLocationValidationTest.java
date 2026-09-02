package com.thang.chargeops.station.dto;

import com.thang.chargeops.exception.errormessage.StationErrorMessage;
import com.thang.chargeops.station.dto.station.filter.StationDiscoveryFilter;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class StationDiscoveryLocationValidationTest {

    @Test
    void allowsDiscoveryWithoutLocation() {
        assertThat(errorsFor(new StationDiscoveryFilter())).isEmpty();
    }

    @Test
    void attachesMissingCoordinatesToFrontendFieldNames() {
        StationDiscoveryFilter filter = new StationDiscoveryFilter();
        filter.setMaxDistanceKm(new BigDecimal("5.0"));

        Map<String, Set<String>> errors = errorsFor(filter);

        assertThat(errors.get("latitude"))
                .containsExactly(StationErrorMessage.STATION_LATITUDE_REQUIRED_KEY);
        assertThat(errors.get("longitude"))
                .containsExactly(StationErrorMessage.STATION_LONGITUDE_REQUIRED_KEY);
    }

    @Test
    void requiresLongitudeWhenOnlyLatitudeIsPresent() {
        StationDiscoveryFilter filter = new StationDiscoveryFilter();
        filter.setLatitude(new BigDecimal("10.776900"));

        Map<String, Set<String>> errors = errorsFor(filter);

        assertThat(errors).containsOnlyKeys("longitude");
        assertThat(errors.get("longitude"))
                .containsExactly(StationErrorMessage.STATION_LONGITUDE_REQUIRED_KEY);
    }

    @Test
    void allowsACompleteCoordinatePair() {
        StationDiscoveryFilter filter = new StationDiscoveryFilter();
        filter.setLatitude(new BigDecimal("10.776900"));
        filter.setLongitude(new BigDecimal("106.700900"));

        assertThat(errorsFor(filter)).isEmpty();
    }

    private Map<String, Set<String>> errorsFor(StationDiscoveryFilter filter) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(filter).stream()
                    .collect(Collectors.groupingBy(
                            violation -> violation.getPropertyPath().toString(),
                            Collectors.mapping(
                                    ConstraintViolation::getMessage,
                                    Collectors.toSet()
                            )
                    ));
        }
    }
}