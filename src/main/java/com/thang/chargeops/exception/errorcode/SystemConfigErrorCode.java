package com.thang.chargeops.exception.errorcode;

import com.thang.chargeops.exception.errormessage.ErrorMessage;
import com.thang.chargeops.exception.errormessage.SystemConfigErrorMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SystemConfigErrorCode implements BaseErrorCode {
    KEY_REQUIRED(HttpStatus.BAD_REQUEST, "CONFIG_001", SystemConfigErrorMessage.KEY_REQUIRED),
    VALUE_REQUIRED(HttpStatus.BAD_REQUEST, "CONFIG_002", SystemConfigErrorMessage.VALUE_REQUIRED),
    NOT_FOUND(HttpStatus.NOT_FOUND, "CONFIG_003", SystemConfigErrorMessage.NOT_FOUND),
    INVALID_FORMAT(HttpStatus.BAD_REQUEST, "CONFIG_004", SystemConfigErrorMessage.INVALID_FORMAT),
    OUT_OF_RANGE(HttpStatus.BAD_REQUEST, "CONFIG_005", SystemConfigErrorMessage.OUT_OF_RANGE),
    UNSUPPORTED_KEY(HttpStatus.BAD_REQUEST, "CONFIG_006", SystemConfigErrorMessage.UNSUPPORTED_KEY),
    REQUIRED_MISSING(HttpStatus.INTERNAL_SERVER_ERROR, "CONFIG_007", SystemConfigErrorMessage.REQUIRED_MISSING),
    STORED_INVALID(HttpStatus.INTERNAL_SERVER_ERROR, "CONFIG_008", SystemConfigErrorMessage.STORED_INVALID);

    private final HttpStatus httpStatus;
    private final String code;
    private final ErrorMessage.Template template;

    @Override
    public String getMessageKey() { return template.key(); }

    @Override
    public String getMessage() { return template.defaultMessage(); }
}
