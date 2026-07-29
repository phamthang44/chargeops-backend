package com.thang.chargeops.exception.errorcode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * System/common error codes. Messages are fixed English defaults (for logs);
 * clients localize by {@link #getCode()}.
 */
@Getter
@RequiredArgsConstructor
public enum CommonErrorCode implements BaseErrorCode {
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "SYS_001", "An unexpected error occurred"),
    DATABASE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "SYS_002", "A database error occurred"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "SYS_003", "Invalid request"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "SYS_004", "HTTP method not allowed"),
    OAUTH_ERROR(HttpStatus.BAD_REQUEST, "SYS_005", "OAuth processing error"),
    DATA_INTEGRITY_ERROR(HttpStatus.CONFLICT, "SYS_006", "Data integrity violation"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "SYS_404", "Resource not found: {0}"),
    RESOURCE_CONFLICT(HttpStatus.CONFLICT, "SYS_409", "Resource conflict");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
