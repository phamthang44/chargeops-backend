package com.thang.chargeops.payment.dto;

import com.thang.chargeops.exception.errormessage.PaymentErrorMessage;
import com.thang.chargeops.payment.dto.request.SimulationRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SimulationRequestValidationTest {

    private static final Instant PAID_AT = Instant.parse("2026-09-21T10:00:00Z");

    @Test
    void reportsRegisteredMessageKeysForMissingRequiredFields() {
        SimulationRequest request = new SimulationRequest(" ", null, 1L, " ", null);

        assertThat(messagesFor(request))
                .contains(
                        PaymentErrorMessage.SIMULATION_TRANSACTION_REF_REQUIRED_KEY,
                        PaymentErrorMessage.SIMULATION_OUTCOME_REQUIRED_KEY,
                        PaymentErrorMessage.SIMULATION_CURRENCY_REQUIRED_KEY,
                        PaymentErrorMessage.SIMULATION_PROVIDER_PAID_AT_REQUIRED_KEY
                );
    }

    @Test
    void reportsRegisteredMessageKeysForInvalidFieldValues() {
        SimulationRequest request = new SimulationRequest(
                "x".repeat(129),
                SimulationRequest.Outcome.SUCCESS,
                -1L,
                "USD",
                PAID_AT
        );

        assertThat(messagesFor(request))
                .containsExactlyInAnyOrder(
                        PaymentErrorMessage.SIMULATION_TRANSACTION_REF_MAX_LENGTH_KEY,
                        PaymentErrorMessage.SIMULATION_AMOUNT_NON_NEGATIVE_KEY,
                        PaymentErrorMessage.SIMULATION_CURRENCY_INVALID_KEY
                );
    }

    private Iterable<String> messagesFor(SimulationRequest request) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            return validator.validate(request).stream()
                    .map(ConstraintViolation::getMessage)
                    .toList();
        }
    }
}
