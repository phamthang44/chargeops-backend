package com.thang.chargeops.payment.dto.request;

import com.thang.chargeops.exception.errormessage.PaymentErrorMessage;
import jakarta.validation.constraints.*;

import java.time.Instant;

public record SimulationRequest(
        @NotBlank(message = PaymentErrorMessage.SIMULATION_TRANSACTION_REF_REQUIRED_KEY)
        @Size(max = 128, message = PaymentErrorMessage.SIMULATION_TRANSACTION_REF_MAX_LENGTH_KEY)
        String transactionRef,

        @NotNull(message = PaymentErrorMessage.SIMULATION_OUTCOME_REQUIRED_KEY)
        Outcome outcome,

        @PositiveOrZero(message = PaymentErrorMessage.SIMULATION_AMOUNT_NON_NEGATIVE_KEY)
        long amount,

        @NotBlank(message = PaymentErrorMessage.SIMULATION_CURRENCY_REQUIRED_KEY)
        @Pattern(regexp = "VND", message = PaymentErrorMessage.SIMULATION_CURRENCY_INVALID_KEY)
        String currency,

        @NotNull(message = PaymentErrorMessage.SIMULATION_PROVIDER_PAID_AT_REQUIRED_KEY)
        Instant providerPaidAt
) {
    public enum Outcome {
        SUCCESS,
        FAILED,
        TIMEOUT
    }
}
