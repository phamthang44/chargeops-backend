package com.thang.chargeops.exception.errorcode;

import com.thang.chargeops.exception.errormessage.ErrorMessage;
import com.thang.chargeops.exception.errormessage.StaffInvitationErrorMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum StaffInvitationErrorCode implements BaseErrorCode {
    NOT_FOUND(HttpStatus.NOT_FOUND, "STAFF_INVITE_001", StaffInvitationErrorMessage.NOT_FOUND),
    EMAIL_IN_USE(HttpStatus.CONFLICT, "STAFF_INVITE_002", StaffInvitationErrorMessage.EMAIL_IN_USE),
    STATE_CONFLICT(HttpStatus.CONFLICT, "STAFF_INVITE_003", StaffInvitationErrorMessage.STATE_CONFLICT),
    IDENTITY_CONFLICT(HttpStatus.CONFLICT, "STAFF_INVITE_004", StaffInvitationErrorMessage.IDENTITY_CONFLICT),
    IDENTITY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "STAFF_INVITE_005", StaffInvitationErrorMessage.IDENTITY_UNAVAILABLE),
    IDENTITY_SETUP_FAILED(HttpStatus.BAD_GATEWAY, "STAFF_INVITE_006", StaffInvitationErrorMessage.IDENTITY_SETUP_FAILED),
    STATION_UNAVAILABLE(HttpStatus.CONFLICT, "STAFF_INVITE_007", StaffInvitationErrorMessage.STATION_UNAVAILABLE);

    private final HttpStatus httpStatus;
    private final String code;
    private final ErrorMessage.Template template;

    @Override public String getMessageKey() { return template.key(); }
    @Override public String getMessage() { return template.defaultMessage(); }
}
