package com.thang.chargeops.exception.errormessage;

import java.util.List;

import static com.thang.chargeops.exception.errormessage.ErrorMessage.template;

public final class PaymentErrorMessage {
    private PaymentErrorMessage() {
    }

    public static final ErrorMessage.Template CHECKOUT_UNAVAILABLE = template("error.payment.checkoutUnavailable", "Payment checkout is temporarily unavailable");

    static List<ErrorMessage.Template> templates() {
        return List.of(CHECKOUT_UNAVAILABLE);
    }
}
