package com.thang.chargeops.exception;

import com.thang.chargeops.exception.errormessage.CommonErrorMessage;
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
    private final Object details;

    public AppException(BaseErrorCode errorCode, Object... args) {
        super(resolveMessage(errorCode, args));
        this.errorCode = errorCode != null ? errorCode : CommonErrorCode.INTERNAL_ERROR;
        this.details = null;
    }

    public AppException(BaseErrorCode errorCode, Object details, Object[] args) {
        super(resolveMessage(errorCode, args));
        this.errorCode = errorCode != null ? errorCode : CommonErrorCode.INTERNAL_ERROR;
        this.details = details;
    }

    public static AppException withDetails(BaseErrorCode errorCode, Object details, Object... args) {
        return new AppException(errorCode, details, args);
    }

    private static String resolveMessage(BaseErrorCode errorCode, Object... args) {
        if (errorCode == null) {
            return CommonErrorMessage.UNKNOWN_ERROR.defaultMessage();
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
