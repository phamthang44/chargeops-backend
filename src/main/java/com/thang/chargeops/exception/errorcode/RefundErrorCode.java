package com.thang.chargeops.exception.errorcode;

import com.thang.chargeops.exception.errormessage.ErrorMessage;
import com.thang.chargeops.exception.errormessage.RefundErrorMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum RefundErrorCode implements BaseErrorCode {
    EXECUTION_CONFLICT(HttpStatus.CONFLICT, "REF_EXECUTION_CONFLICT", RefundErrorMessage.EXECUTION_CONFLICT),
    AMOUNT_CONFLICT(HttpStatus.CONFLICT, "REF_AMOUNT_CONFLICT", RefundErrorMessage.AMOUNT_CONFLICT),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "REF_VERSION_CONFLICT", RefundErrorMessage.VERSION_CONFLICT),
    REQUEST_CONFLICT(HttpStatus.CONFLICT, "REF_REQUEST_CONFLICT", RefundErrorMessage.REQUEST_CONFLICT),
    MODE_UNAVAILABLE(HttpStatus.CONFLICT, "REF_MODE_UNAVAILABLE", RefundErrorMessage.MODE_UNAVAILABLE),
    INVALID_EXECUTION_REQUEST(HttpStatus.BAD_REQUEST, "REF_INVALID_EXECUTION_REQUEST", RefundErrorMessage.INVALID_EXECUTION_REQUEST);

    private final HttpStatus httpStatus;
    private final String code;
    private final ErrorMessage.Template template;

    @Override
    public String getMessageKey() {
        return template.key();
    }

    @Override
    public String getMessage() {
        return template.defaultMessage();
    }
}
