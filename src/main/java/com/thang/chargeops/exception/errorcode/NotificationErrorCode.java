package com.thang.chargeops.exception.errorcode;

import com.thang.chargeops.exception.errormessage.ErrorMessage;
import com.thang.chargeops.exception.errormessage.NotificationErrorMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum NotificationErrorCode implements BaseErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND, "NTF_NOT_FOUND", NotificationErrorMessage.NOT_FOUND),
    UNKNOWN_RECIPIENT(HttpStatus.NOT_FOUND, "NTF_UNKNOWN_RECIPIENT", NotificationErrorMessage.UNKNOWN_RECIPIENT),
    UNKNOWN_TICKET_SOURCE(HttpStatus.NOT_FOUND, "NTF_UNKNOWN_TICKET_SOURCE", NotificationErrorMessage.UNKNOWN_TICKET_SOURCE),
    TICKET_TARGET_MISMATCH(HttpStatus.BAD_REQUEST, "NTF_TICKET_TARGET_MISMATCH", NotificationErrorMessage.TICKET_TARGET_MISMATCH),
    UNSUPPORTED_EVENT(HttpStatus.BAD_REQUEST, "NTF_UNSUPPORTED_EVENT", NotificationErrorMessage.UNSUPPORTED_EVENT),
    INVALID_EXPIRY(HttpStatus.BAD_REQUEST, "NTF_INVALID_EXPIRY", NotificationErrorMessage.INVALID_EXPIRY),
    INVALID_CONTENT(HttpStatus.BAD_REQUEST, "NTF_INVALID_CONTENT", NotificationErrorMessage.INVALID_CONTENT),
    INVALID_INTENT(HttpStatus.BAD_REQUEST, "NTF_INVALID_INTENT", NotificationErrorMessage.INVALID_INTENT),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "NTF_ACCESS_DENIED", NotificationErrorMessage.ACCESS_DENIED);

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
