package com.thang.chargeops.common.exception;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.SystemConfigErrorCode;

/** Uses the existing AppException handler; messages never include raw configuration values. */
public class SystemConfigException extends AppException {
    public SystemConfigException(SystemConfigErrorCode code) {
        super(code);
    }

    public SystemConfigException(SystemConfigErrorCode code, Throwable cause) {
        super(code);
        initCause(cause);
    }
}
