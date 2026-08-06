package com.thang.chargeops.exception.errorcode;

import com.thang.chargeops.exception.errormessage.ErrorMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements BaseErrorCode {
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "AUTH_001", ErrorMessage.Auth.UNAUTHENTICATED),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "AUTH_002", ErrorMessage.Auth.TOKEN_EXPIRED),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AUTH_003", ErrorMessage.Auth.TOKEN_INVALID),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "AUTH_004", ErrorMessage.Auth.ACCESS_DENIED);

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
