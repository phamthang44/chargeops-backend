package com.thang.chargeops.exception.errorcode;

import com.thang.chargeops.exception.errormessage.ErrorMessage;
import com.thang.chargeops.exception.errormessage.IncidentErrorMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum IncidentErrorCode implements BaseErrorCode {
    VERSION_CONFLICT(HttpStatus.CONFLICT, "INC_VERSION_CONFLICT", IncidentErrorMessage.VERSION_CONFLICT),
    STATE_CONFLICT(HttpStatus.CONFLICT, "INC_STATE_CONFLICT", IncidentErrorMessage.STATE_CONFLICT),
    OPEN_INCIDENT(HttpStatus.CONFLICT, "INC_OPEN_INCIDENT", IncidentErrorMessage.OPEN_INCIDENT),
    ALREADY_OPEN(HttpStatus.CONFLICT, "INC_ALREADY_OPEN", IncidentErrorMessage.ALREADY_OPEN);
    private final HttpStatus httpStatus;
    private final String code;
    private final ErrorMessage.Template template;
    @Override public String getMessageKey() { return template.key(); }
    @Override public String getMessage() { return template.defaultMessage(); }
}
