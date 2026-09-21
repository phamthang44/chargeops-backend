package com.thang.chargeops.exception.errormessage;

import java.util.List;

import static com.thang.chargeops.exception.errormessage.ErrorMessage.template;

public final class PaymentErrorMessage {
    public static final String SIMULATION_TRANSACTION_REF_REQUIRED_KEY =
            "validation.payment.simulation.transactionRef.required";
    public static final String SIMULATION_TRANSACTION_REF_MAX_LENGTH_KEY =
            "validation.payment.simulation.transactionRef.maxLength";
    public static final String SIMULATION_OUTCOME_REQUIRED_KEY =
            "validation.payment.simulation.outcome.required";
    public static final String SIMULATION_AMOUNT_NON_NEGATIVE_KEY =
            "validation.payment.simulation.amount.nonNegative";
    public static final String SIMULATION_CURRENCY_REQUIRED_KEY =
            "validation.payment.simulation.currency.required";
    public static final String SIMULATION_CURRENCY_INVALID_KEY =
            "validation.payment.simulation.currency.invalid";
    public static final String SIMULATION_PROVIDER_PAID_AT_REQUIRED_KEY =
            "validation.payment.simulation.providerPaidAt.required";

    private PaymentErrorMessage() {
    }

    public static final ErrorMessage.Template CHECKOUT_UNAVAILABLE = template("error.payment.checkoutUnavailable", "Payment checkout is temporarily unavailable");
    public static final ErrorMessage.Template NOT_FOUND = template("error.payment.notFound", "Payment record not found");
    public static final ErrorMessage.Template STATE_CONFLICT = template("error.payment.stateConflict", "Payment cannot be transitioned in its current state");
    public static final ErrorMessage.Template METHOD_INVALID = template("error.payment.methodInvalid", "Payment method is invalid or unsupported");
    public static final ErrorMessage.Template AMOUNT_INVALID = template("error.payment.amountInvalid", "Payment or receipt amount is invalid");
    public static final ErrorMessage.Template RECONCILIATION_REQUIRED = template("error.payment.reconciliationRequired", "Payment requires manual reconciliation before financial mutations");
    public static final ErrorMessage.Template PROJECTION_BOUNDS_VIOLATED = template("error.payment.projectionBoundsViolated", "Payment projection bounds or balance equation violated");
    public static final ErrorMessage.Template CHECKOUT_REQUIRED = template(
            "error.payment.checkoutRequired",
            "Create the payment checkout before simulating its outcome"
    );
    public static final ErrorMessage.Template RECEIPT_INVALID = template(
            "error.payment.receiptInvalid",
            "Payment receipt evidence is invalid"
    );
    public static final ErrorMessage.Template SIMULATION_REQUEST_INVALID = template(
            "error.payment.simulationRequestInvalid",
            "Payment simulation request is invalid"
    );
    public static final ErrorMessage.Template SIMULATION_TRANSACTION_REF_REQUIRED = template(
            SIMULATION_TRANSACTION_REF_REQUIRED_KEY,
            "Simulation transaction reference is required"
    );
    public static final ErrorMessage.Template SIMULATION_TRANSACTION_REF_MAX_LENGTH = template(
            SIMULATION_TRANSACTION_REF_MAX_LENGTH_KEY,
            "Simulation transaction reference cannot exceed 128 characters"
    );
    public static final ErrorMessage.Template SIMULATION_OUTCOME_REQUIRED = template(
            SIMULATION_OUTCOME_REQUIRED_KEY,
            "Simulation outcome is required"
    );
    public static final ErrorMessage.Template SIMULATION_AMOUNT_NON_NEGATIVE = template(
            SIMULATION_AMOUNT_NON_NEGATIVE_KEY,
            "Simulation amount cannot be negative"
    );
    public static final ErrorMessage.Template SIMULATION_CURRENCY_REQUIRED = template(
            SIMULATION_CURRENCY_REQUIRED_KEY,
            "Simulation currency is required"
    );
    public static final ErrorMessage.Template SIMULATION_CURRENCY_INVALID = template(
            SIMULATION_CURRENCY_INVALID_KEY,
            "Simulation currency must be VND"
    );
    public static final ErrorMessage.Template SIMULATION_PROVIDER_PAID_AT_REQUIRED = template(
            SIMULATION_PROVIDER_PAID_AT_REQUIRED_KEY,
            "Provider payment time is required"
    );

    static List<ErrorMessage.Template> templates() {
        return List.of(
                CHECKOUT_UNAVAILABLE,
                NOT_FOUND,
                STATE_CONFLICT,
                METHOD_INVALID,
                AMOUNT_INVALID,
                RECONCILIATION_REQUIRED,
                PROJECTION_BOUNDS_VIOLATED,
                CHECKOUT_REQUIRED,
                RECEIPT_INVALID,
                SIMULATION_REQUEST_INVALID,
                SIMULATION_TRANSACTION_REF_REQUIRED,
                SIMULATION_TRANSACTION_REF_MAX_LENGTH,
                SIMULATION_OUTCOME_REQUIRED,
                SIMULATION_AMOUNT_NON_NEGATIVE,
                SIMULATION_CURRENCY_REQUIRED,
                SIMULATION_CURRENCY_INVALID,
                SIMULATION_PROVIDER_PAID_AT_REQUIRED
        );
    }
}
