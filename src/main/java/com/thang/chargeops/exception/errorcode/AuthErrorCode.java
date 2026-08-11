package com.thang.chargeops.exception.errorcode;

import com.thang.chargeops.exception.errormessage.AuthErrorMessage;
import com.thang.chargeops.exception.errormessage.ErrorMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements BaseErrorCode {
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "AUTH_001", AuthErrorMessage.UNAUTHENTICATED),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "AUTH_002", AuthErrorMessage.TOKEN_EXPIRED),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AUTH_003", AuthErrorMessage.TOKEN_INVALID),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "AUTH_004", AuthErrorMessage.ACCESS_DENIED),
    EMAIL_CLAIM_MISSING(HttpStatus.BAD_REQUEST, "AUTH_005", AuthErrorMessage.EMAIL_CLAIM_MISSING);

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
