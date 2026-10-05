package com.thang.chargeops.exception.errorcode;

import com.thang.chargeops.exception.errormessage.ErrorMessage;
import com.thang.chargeops.exception.errormessage.TicketErrorMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum TicketErrorCode implements BaseErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND, "TKT_NOT_FOUND", TicketErrorMessage.NOT_FOUND),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "TKT_ACCESS_DENIED", TicketErrorMessage.ACCESS_DENIED),
    CLAIM_REQUIRED(HttpStatus.CONFLICT, "TKT_CLAIM_REQUIRED", TicketErrorMessage.CLAIM_REQUIRED),
    NOT_CURRENT_HANDLER(HttpStatus.FORBIDDEN, "TKT_NOT_CURRENT_HANDLER", TicketErrorMessage.NOT_CURRENT_HANDLER),
    INVALID_SCOPE(HttpStatus.BAD_REQUEST, "TKT_INVALID_SCOPE", TicketErrorMessage.INVALID_SCOPE),
    STATE_CONFLICT(HttpStatus.CONFLICT, "TKT_STATE_CONFLICT", TicketErrorMessage.STATE_CONFLICT),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "TKT_VERSION_CONFLICT", TicketErrorMessage.VERSION_CONFLICT),
    CLOSED(HttpStatus.CONFLICT, "TKT_CLOSED", TicketErrorMessage.CLOSED),
    ASSIGNMENT_INVALID(HttpStatus.BAD_REQUEST, "TKT_ASSIGNMENT_INVALID", TicketErrorMessage.ASSIGNMENT_INVALID),
    FINDING_INVALID(HttpStatus.BAD_REQUEST, "TKT_FINDING_INVALID", TicketErrorMessage.FINDING_INVALID),
    CODE_CONFLICT(HttpStatus.CONFLICT, "TKT_CODE_CONFLICT", TicketErrorMessage.CODE_CONFLICT);

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
