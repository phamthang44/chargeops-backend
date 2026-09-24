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

    static List<ErrorMessage.Template> templates() {
        return List.of(EXECUTION_CONFLICT, AMOUNT_CONFLICT);
    }
}
