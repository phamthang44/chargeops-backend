package com.thang.chargeops.exception.errorcode;

import com.thang.chargeops.exception.errormessage.ErrorMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements BaseErrorCode {
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "SYS_001", ErrorMessage.Common.INTERNAL_ERROR),
    DATABASE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "SYS_002", ErrorMessage.Common.DATABASE_ERROR),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "SYS_003", ErrorMessage.Common.INVALID_REQUEST),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "SYS_004", ErrorMessage.Common.METHOD_NOT_ALLOWED),
    OAUTH_ERROR(HttpStatus.BAD_REQUEST, "SYS_005", ErrorMessage.Common.OAUTH_ERROR),
    DATA_INTEGRITY_ERROR(HttpStatus.CONFLICT, "SYS_006", ErrorMessage.Common.DATA_INTEGRITY_ERROR),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "SYS_404", ErrorMessage.Common.RESOURCE_NOT_FOUND),
    RESOURCE_CONFLICT(HttpStatus.CONFLICT, "SYS_409", ErrorMessage.Common.RESOURCE_CONFLICT);

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
