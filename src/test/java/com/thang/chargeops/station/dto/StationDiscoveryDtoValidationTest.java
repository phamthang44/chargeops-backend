package com.thang.chargeops.station.dto;

import com.thang.chargeops.common.enums.ConnectorType;
import com.thang.chargeops.exception.errormessage.ErrorMessage;
import com.thang.chargeops.exception.errormessage.StationErrorMessage;
import com.thang.chargeops.station.dto.station.filter.StationAvailabilityQuery;
import com.thang.chargeops.station.dto.station.filter.StationDiscoveryFilter;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class StationDiscoveryDtoValidationTest {

    @Test
    void registersDiscoveryValidationMessages() {
        assertThat(ErrorMessage.defaultMessage(StationErrorMessage.DISCOVERY_QUERY_MAX_LENGTH_KEY))
                .isEqualTo("Discovery query cannot exceed 200 characters");
        assertThat(ErrorMessage.defaultMessage(StationErrorMessage.DISCOVERY_MAX_DISTANCE_MIN_KEY))
                .isEqualTo("Discovery distance must be greater than zero");
        assertThat(ErrorMessage.defaultMessage(StationErrorMessage.AVAILABILITY_CONNECTOR_REQUIRED_KEY))
                .isEqualTo("Connector is required to check availability");
        assertThat(ErrorMessage.defaultMessage(StationErrorMessage.AVAILABILITY_DATE_REQUIRED_KEY))
                .isEqualTo("Availability date is required");
    }

    @Test
    void usesDiscoverySpecificKeysForInvalidFilterFields() {
        StationDiscoveryFilter filter = new StationDiscoveryFilter();
        filter.setQuery("x".repeat(201));
        filter.setConnectorTypes(setReportingSize(5));
        filter.setProvinceCode("1".repeat(21));
        filter.setMinPowerKw(new BigDecimal("-0.1"));
        filter.setLatitude(new BigDecimal("91"));
        filter.setLongitude(new BigDecimal("181"));
        filter.setMaxDistanceKm(BigDecimal.ZERO);

        Map<String, Set<String>> messagesByField = validate(filter);

        assertThat(messagesByField.get("query"))
                .contains(StationErrorMessage.DISCOVERY_QUERY_MAX_LENGTH_KEY);
        assertThat(messagesByField.get("connectorTypes"))
                .contains(StationErrorMessage.DISCOVERY_CONNECTOR_TYPES_MAX_KEY);
        assertThat(messagesByField.get("provinceCode"))
                .contains(StationErrorMessage.DISCOVERY_PROVINCE_CODE_MAX_LENGTH_KEY);
        assertThat(messagesByField.get("minPowerKw"))
                .contains(StationErrorMessage.DISCOVERY_MIN_POWER_MIN_KEY);
        assertThat(messagesByField.get("latitude"))
                .contains(StationErrorMessage.STATION_LATITUDE_INVALID_KEY);
        assertThat(messagesByField.get("longitude"))
                .contains(StationErrorMessage.STATION_LONGITUDE_INVALID_KEY);
        assertThat(messagesByField.get("maxDistanceKm"))
                .contains(StationErrorMessage.DISCOVERY_MAX_DISTANCE_MIN_KEY);
        assertThat(messagesByField.values().stream().flatMap(Set::stream))
                .noneMatch(message -> message.startsWith("jakarta.validation.constraints."));
    }

    @Test
    void usesAvailabilitySpecificKeysForMissingRequiredFields() {
        Map<String, Set<String>> messagesByField = validate(new StationAvailabilityQuery());

        assertThat(messagesByField.get("connectorId"))
                .contains(StationErrorMessage.AVAILABILITY_CONNECTOR_REQUIRED_KEY);
        assertThat(messagesByField.get("date"))
                .contains(StationErrorMessage.AVAILABILITY_DATE_REQUIRED_KEY);
    }

    private Map<String, Set<String>> validate(Object value) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(value).stream()
                    .collect(Collectors.groupingBy(
                            violation -> violation.getPropertyPath().toString(),
                            Collectors.mapping(violation -> violation.getMessage(), Collectors.toSet())
                    ));
        }
    }

    private Set<ConnectorType> setReportingSize(int size) {
        return new AbstractSet<>() {
            @Override
            public Iterator<ConnectorType> iterator() {
                return Collections.emptyIterator();
            }

            @Override
            public int size() {
                return size;
            }
        };
    }
}
