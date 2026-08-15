package com.thang.chargeops.exception.errorcode;

import com.thang.chargeops.exception.errormessage.ErrorMessage;
import com.thang.chargeops.exception.errormessage.ProfileErrorMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ProfileErrorCode implements BaseErrorCode {
    EMAIL_ALREADY_LINKED(HttpStatus.CONFLICT, "PROFILE_001", ProfileErrorMessage.EMAIL_ALREADY_LINKED),
    PROFILE_BOOTSTRAP_FAILED(
            HttpStatus.BAD_REQUEST, "PROFILE_002", ProfileErrorMessage.PROFILE_BOOTSTRAP_FAILED),
    PROFILE_NOT_FOUND(HttpStatus.NOT_FOUND, "PROFILE_003", ProfileErrorMessage.PROFILE_NOT_FOUND),
    PROFILE_NOT_ACTIVE(HttpStatus.FORBIDDEN, "PROFILE_004", ProfileErrorMessage.PROFILE_NOT_ACTIVE);

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
