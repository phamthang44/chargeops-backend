package com.thang.chargeops.exception.errorcode;

import com.thang.chargeops.exception.errormessage.ErrorMessage;
import com.thang.chargeops.exception.errormessage.UserErrorMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/** UM-02: error codes admin user management V1 (UM-00 §6.2). */
@Getter
@RequiredArgsConstructor
public enum UserErrorCode implements BaseErrorCode {
    TARGET_PROTECTED(HttpStatus.FORBIDDEN, "USER_001", UserErrorMessage.TARGET_PROTECTED),
    REASON_INVALID(HttpStatus.BAD_REQUEST, "USER_002", UserErrorMessage.REASON_INVALID),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "USER_003", UserErrorMessage.VERSION_CONFLICT),
    SUSPEND_BLOCKED(HttpStatus.CONFLICT, "USER_004", UserErrorMessage.SUSPEND_BLOCKED),
    ROLE_UNVERIFIED(HttpStatus.SERVICE_UNAVAILABLE, "USER_005", UserErrorMessage.ROLE_UNVERIFIED),
    OWNER_MANAGED_OFFLINE(HttpStatus.FORBIDDEN, "USER_007", UserErrorMessage.OWNER_MANAGED_OFFLINE);

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
