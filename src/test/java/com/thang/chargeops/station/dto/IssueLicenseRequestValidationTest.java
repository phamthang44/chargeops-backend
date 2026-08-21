package com.thang.chargeops.station.dto;

import com.thang.chargeops.common.enums.Plan;
import com.thang.chargeops.exception.errormessage.LicenseErrorMessage;
import com.thang.chargeops.station.dto.license.request.IssueLicenseRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class IssueLicenseRequestValidationTest {

    @Test
    void usesLicenseSpecificKeyWhenPlanIsMissing() {
        IssueLicenseRequest request = new IssueLicenseRequest(null);

        Map<String, Set<String>> messagesByField = validate(request);

        assertThat(messagesByField.get("plan"))
                .containsExactly(LicenseErrorMessage.PLAN_REQUIRED_KEY);
    }

    @Test
    void acceptsSupportedPlanWithoutClientSuppliedFee() {
        assertThat(validate(new IssueLicenseRequest(Plan.MONTHLY))).isEmpty();
        assertThat(validate(new IssueLicenseRequest(Plan.YEARLY))).isEmpty();
    }

    private Map<String, Set<String>> validate(IssueLicenseRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request).stream()
                    .collect(Collectors.groupingBy(
                            violation -> violation.getPropertyPath().toString(),
                            Collectors.mapping(violation -> violation.getMessage(), Collectors.toSet())
                    ));
        }
    }
}
