package com.thang.chargeops.exception.errorcode;

import com.thang.chargeops.exception.errormessage.ErrorMessage;
import com.thang.chargeops.exception.errormessage.ModerationErrorMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ModerationErrorCode implements BaseErrorCode {
    CONTENT_PROHIBITED(HttpStatus.BAD_REQUEST, "CONTENT_PROHIBITED", ModerationErrorMessage.PROHIBITED),
    MODERATION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "MODERATION_UNAVAILABLE", ModerationErrorMessage.UNAVAILABLE),
    TERM_NOT_FOUND(HttpStatus.NOT_FOUND, "MODERATION_NOT_FOUND", ModerationErrorMessage.NOT_FOUND),
    TERM_DUPLICATE(HttpStatus.CONFLICT, "MODERATION_DUPLICATE", ModerationErrorMessage.DUPLICATE),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "MODERATION_VERSION_CONFLICT", ModerationErrorMessage.CONFLICT),
    INVALID_RULE(HttpStatus.BAD_REQUEST, "MODERATION_INVALID_RULE", ModerationErrorMessage.INVALID);

    private final HttpStatus httpStatus;
    private final String code;
    private final ErrorMessage.Template template;
    public String getMessageKey() { return template.key(); }
    public String getMessage() { return template.defaultMessage(); }
}
