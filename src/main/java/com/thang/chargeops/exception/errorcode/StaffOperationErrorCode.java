package com.thang.chargeops.exception.errorcode;

import com.thang.chargeops.exception.errormessage.ErrorMessage;
import com.thang.chargeops.exception.errormessage.StaffOperationErrorMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum StaffOperationErrorCode implements BaseErrorCode {
    VERSION_CONFLICT(HttpStatus.CONFLICT, "STAFF_OP_001", StaffOperationErrorMessage.VERSION_CONFLICT),
    ACTIVE_SERVICE(HttpStatus.CONFLICT, "STAFF_OP_002", StaffOperationErrorMessage.ACTIVE_SERVICE),
    STATION_UNAVAILABLE(HttpStatus.CONFLICT, "STAFF_OP_003", StaffOperationErrorMessage.STATION_UNAVAILABLE),
    INVALID_TRANSITION(HttpStatus.CONFLICT, "STAFF_OP_004", StaffOperationErrorMessage.INVALID_TRANSITION),
    REASON_REQUIRED(HttpStatus.BAD_REQUEST, "STAFF_OP_005", StaffOperationErrorMessage.REASON_REQUIRED);

    private final HttpStatus httpStatus;
    private final String code;
    private final ErrorMessage.Template template;

    @Override public String getMessageKey() { return template.key(); }
    @Override public String getMessage() { return template.defaultMessage(); }
}
