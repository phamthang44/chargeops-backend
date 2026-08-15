package com.thang.chargeops.station.dto;

import com.thang.chargeops.exception.errormessage.ApprovalErrorMessage;
import com.thang.chargeops.station.dto.station.request.RejectStationRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class RejectStationRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsBlankReasonWithRegisteredMessageKey() {
        var request = requestWithReason(" ");

        assertThat(validator.validate(request))
                .extracting(ConstraintViolation::getMessage)
                .containsExactly(ApprovalErrorMessage.REJECTION_REASON_VALIDATION_REQUIRED_KEY);
    }

    @Test
    void rejectsReasonLongerThanFiveHundredCharactersWithRegisteredMessageKey() {
        var request = requestWithReason("x".repeat(501));

        assertThat(validator.validate(request))
                .extracting(ConstraintViolation::getMessage)
                .containsExactly(ApprovalErrorMessage.REJECTION_REASON_MAX_LENGTH_KEY);
    }

    private RejectStationRequest requestWithReason(String reason) {
        var request = new RejectStationRequest();
        ReflectionTestUtils.setField(request, "reason", reason);
        return request;
    }
}
