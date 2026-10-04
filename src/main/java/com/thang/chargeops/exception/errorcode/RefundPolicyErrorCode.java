package com.thang.chargeops.exception.errorcode;

import com.thang.chargeops.exception.errormessage.ErrorMessage;
import com.thang.chargeops.exception.errormessage.RefundErrorMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum RefundPolicyErrorCode implements BaseErrorCode {
    REFUND_ALREADY_GRANTED(HttpStatus.CONFLICT, "RFD_REFUND_ALREADY_GRANTED",
            RefundErrorMessage.POLICY_ALREADY_GRANTED);

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
