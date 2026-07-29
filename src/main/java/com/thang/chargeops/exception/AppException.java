package com.thang.chargeops.exception;

import com.thang.chargeops.exception.errorcode.BaseErrorCode;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

@Getter
@Slf4j
public class AppException extends RuntimeException {

    private final BaseErrorCode errorCode;

    public AppException(BaseErrorCode errorCode, Object... args) {
        super(resolveMessage(errorCode, args));
        this.errorCode = errorCode != null ? errorCode : CommonErrorCode.INTERNAL_ERROR;
    }

    private static String resolveMessage(BaseErrorCode errorCode, Object... args) {
        if (errorCode == null) {
            return "Unknown error";
        }
        return errorCode.format(args);
    }

    public String getErrorCodeStr() {
        return errorCode.getCode();
    }

    public HttpStatus getHttpStatus() {
        return errorCode.getHttpStatus();
    }
}