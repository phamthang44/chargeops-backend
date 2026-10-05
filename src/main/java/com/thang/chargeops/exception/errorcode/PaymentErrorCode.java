package com.thang.chargeops.exception.errorcode;

import com.thang.chargeops.exception.errormessage.ErrorMessage;
import com.thang.chargeops.exception.errormessage.PaymentErrorMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PaymentErrorCode implements BaseErrorCode {
    CHECKOUT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "PAY_CHECKOUT_UNAVAILABLE", PaymentErrorMessage.CHECKOUT_UNAVAILABLE),
    NOT_FOUND(HttpStatus.NOT_FOUND, "PAY_NOT_FOUND", PaymentErrorMessage.NOT_FOUND),
    STATE_CONFLICT(HttpStatus.CONFLICT, "PAY_STATE_CONFLICT", PaymentErrorMessage.STATE_CONFLICT),
    METHOD_INVALID(HttpStatus.BAD_REQUEST, "PAY_METHOD_INVALID", PaymentErrorMessage.METHOD_INVALID),
    AMOUNT_INVALID(HttpStatus.BAD_REQUEST, "PAY_AMOUNT_INVALID", PaymentErrorMessage.AMOUNT_INVALID),
    RECONCILIATION_REQUIRED(HttpStatus.CONFLICT, "PAY_RECONCILIATION_REQUIRED", PaymentErrorMessage.RECONCILIATION_REQUIRED),
    PROJECTION_BOUNDS_VIOLATED(HttpStatus.CONFLICT, "PAY_PROJECTION_BOUNDS_VIOLATED", PaymentErrorMessage.PROJECTION_BOUNDS_VIOLATED),
    CHECKOUT_REQUIRED(HttpStatus.CONFLICT, "PAY_CHECKOUT_REQUIRED", PaymentErrorMessage.CHECKOUT_REQUIRED),
    RECEIPT_INVALID(HttpStatus.BAD_REQUEST, "PAY_RECEIPT_INVALID", PaymentErrorMessage.RECEIPT_INVALID),
    SIMULATION_REQUEST_INVALID(HttpStatus.BAD_REQUEST, "PAY_SIMULATION_REQUEST_INVALID", PaymentErrorMessage.SIMULATION_REQUEST_INVALID);

    private final HttpStatus httpStatus;
    private final String code;
    private final ErrorMessage.Template template;

    @Override public String getMessageKey() { return template.key(); }
    @Override public String getMessage() { return template.defaultMessage(); }
}
