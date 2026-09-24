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
    AMOUNT_CONFLICT(HttpStatus.CONFLICT, "REF_AMOUNT_CONFLICT", RefundErrorMessage.AMOUNT_CONFLICT);

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
