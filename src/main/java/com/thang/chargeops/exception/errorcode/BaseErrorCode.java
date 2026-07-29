package com.thang.chargeops.exception.errorcode;

import org.springframework.http.HttpStatus;

import java.text.MessageFormat;

/**
 * Contract for application error codes.
 *
 * <p>{@link #getMessage()} is a fixed English default, intended for logs and as a
 * fallback for non-UI consumers. User-facing localization is the <b>frontend's</b>
 * responsibility, keyed by {@link #getCode()} — the backend does not translate.
 */
public interface BaseErrorCode {

    String getCode();

    String getMessage();

    HttpStatus getHttpStatus();

    /** Substitutes {@code {0}, {1}, ...} placeholders into the default message. */
    default String format(Object... args) {
        if (args == null || args.length == 0) {
            return getMessage();
        }
        try {
            return MessageFormat.format(getMessage(), args);
        } catch (Exception e) {
            return getMessage();
        }
    }
}
