package com.thang.chargeops.exception.errorcode;

import com.thang.chargeops.exception.errormessage.ApprovalErrorMessage;
import com.thang.chargeops.exception.errormessage.ErrorMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ApprovalErrorCode implements BaseErrorCode {

    STATION_NOT_PENDING_APPROVAL(
            HttpStatus.CONFLICT,
            "APPROVAL_001",
            ApprovalErrorMessage.STATION_NOT_PENDING_APPROVAL
    ),
    REJECTION_REASON_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "APPROVAL_002",
            ApprovalErrorMessage.REJECTION_REASON_REQUIRED
    ),
    ACTIVE_LICENSE_REQUIRED(
            HttpStatus.CONFLICT,
            "APPROVAL_003",
            ApprovalErrorMessage.ACTIVE_LICENSE_REQUIRED
    );

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
