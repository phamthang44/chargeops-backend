package com.thang.chargeops.exception;

import com.thang.chargeops.exception.errormessage.ErrorMessage;
import com.thang.chargeops.exception.errorcode.BaseErrorCode;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.io.Serial;

@Getter
@Slf4j
public class AppException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;
    private final BaseErrorCode errorCode;

    public AppException(BaseErrorCode errorCode, Object... args) {
        super(resolveMessage(errorCode, args));
        this.errorCode = errorCode != null ? errorCode : CommonErrorCode.INTERNAL_ERROR;
    }

    private static String resolveMessage(BaseErrorCode errorCode, Object... args) {
        if (errorCode == null) {
            return ErrorMessage.Common.UNKNOWN_ERROR.defaultMessage();
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
