package com.thang.chargeops.exception.errormessage;

import java.util.List;

import static com.thang.chargeops.exception.errormessage.ErrorMessage.template;

public final class RefundErrorMessage {
    private RefundErrorMessage() {
    }

    public static final ErrorMessage.Template EXECUTION_CONFLICT = template(
            "error.refund.executionConflict",
            "Refund cannot be created or transitioned in its current state"
    );

    public static final ErrorMessage.Template AMOUNT_CONFLICT = template(
            "error.refund.amountConflict",
            "Refund must match the accepted full-package amount in whole VND"
    );

    public static final ErrorMessage.Template VERSION_CONFLICT = template(
            "error.refund.versionConflict",
            "Refund data changed; refresh before executing it"
    );

    public static final ErrorMessage.Template REQUEST_CONFLICT = template(
            "error.refund.requestConflict",
            "The refund request key was already used with a different payload"
    );

    public static final ErrorMessage.Template MODE_UNAVAILABLE = template(
            "error.refund.modeUnavailable",
            "The requested refund execution mode is unavailable in this environment"
    );

    public static final ErrorMessage.Template INVALID_EXECUTION_REQUEST = template(
            "error.refund.invalidExecutionRequest",
            "Refund execution data is invalid for the selected mode"
    );

    static List<ErrorMessage.Template> templates() {
        return List.of(
                EXECUTION_CONFLICT,
                AMOUNT_CONFLICT,
                VERSION_CONFLICT,
                REQUEST_CONFLICT,
                MODE_UNAVAILABLE,
                INVALID_EXECUTION_REQUEST
        );
    }
}
