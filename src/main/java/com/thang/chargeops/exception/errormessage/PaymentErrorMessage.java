package com.thang.chargeops.exception.errormessage;

import java.util.List;

import static com.thang.chargeops.exception.errormessage.ErrorMessage.template;

public final class PaymentErrorMessage {
    private PaymentErrorMessage() {
    }

    public static final ErrorMessage.Template CHECKOUT_UNAVAILABLE = template("error.payment.checkoutUnavailable", "Payment checkout is temporarily unavailable");
    public static final ErrorMessage.Template NOT_FOUND = template("error.payment.notFound", "Payment record not found");
    public static final ErrorMessage.Template STATE_CONFLICT = template("error.payment.stateConflict", "Payment cannot be transitioned in its current state");
    public static final ErrorMessage.Template METHOD_INVALID = template("error.payment.methodInvalid", "Payment method is invalid or unsupported");
    public static final ErrorMessage.Template AMOUNT_INVALID = template("error.payment.amountInvalid", "Payment or receipt amount is invalid");
    public static final ErrorMessage.Template RECONCILIATION_REQUIRED = template("error.payment.reconciliationRequired", "Payment requires manual reconciliation before financial mutations");
    public static final ErrorMessage.Template PROJECTION_BOUNDS_VIOLATED = template("error.payment.projectionBoundsViolated", "Payment projection bounds or balance equation violated");

    static List<ErrorMessage.Template> templates() {
        return List.of(
                CHECKOUT_UNAVAILABLE,
                NOT_FOUND,
                STATE_CONFLICT,
                METHOD_INVALID,
                AMOUNT_INVALID,
                RECONCILIATION_REQUIRED,
                PROJECTION_BOUNDS_VIOLATED
        );
    }
}
