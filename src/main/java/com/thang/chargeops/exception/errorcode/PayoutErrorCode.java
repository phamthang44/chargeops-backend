package com.thang.chargeops.exception.errorcode;

import com.thang.chargeops.exception.errormessage.ErrorMessage;
import com.thang.chargeops.exception.errormessage.PayoutErrorMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PayoutErrorCode implements BaseErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND, "PAYOUT_NOT_FOUND", PayoutErrorMessage.NOT_FOUND),
    CODE_CONFLICT(HttpStatus.CONFLICT, "PAYOUT_CODE_CONFLICT", PayoutErrorMessage.CODE_CONFLICT),
    STATE_CONFLICT(HttpStatus.CONFLICT, "PAYOUT_STATE_CONFLICT", PayoutErrorMessage.STATE_CONFLICT),
    BOOKING_NOT_ELIGIBLE(HttpStatus.CONFLICT, "PAYOUT_BOOKING_NOT_ELIGIBLE", PayoutErrorMessage.BOOKING_NOT_ELIGIBLE),
    BOOKING_ALREADY_RESERVED(HttpStatus.CONFLICT, "PAYOUT_BOOKING_ALREADY_RESERVED", PayoutErrorMessage.BOOKING_ALREADY_RESERVED),
    ENVIRONMENT_MISMATCH(HttpStatus.CONFLICT, "PAYOUT_ENVIRONMENT_MISMATCH", PayoutErrorMessage.ENVIRONMENT_MISMATCH),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "PAYOUT_VERSION_CONFLICT", PayoutErrorMessage.VERSION_CONFLICT),
    REQUEST_CONFLICT(HttpStatus.CONFLICT, "PAYOUT_REQUEST_CONFLICT", PayoutErrorMessage.REQUEST_CONFLICT),
    CANNOT_CANCEL(HttpStatus.CONFLICT, "PAYOUT_CANNOT_CANCEL", PayoutErrorMessage.CANNOT_CANCEL);

    private final HttpStatus httpStatus;
    private final String code;
    private final ErrorMessage.Template template;

    @Override public String getMessageKey() { return template.key(); }
    @Override public String getMessage() { return template.defaultMessage(); }
}
