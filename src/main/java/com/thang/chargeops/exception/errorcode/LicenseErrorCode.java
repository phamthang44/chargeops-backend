package com.thang.chargeops.exception.errorcode;

import com.thang.chargeops.exception.errormessage.ErrorMessage;
import com.thang.chargeops.exception.errormessage.LicenseErrorMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum LicenseErrorCode implements BaseErrorCode {

    LICENSE_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "LICENSE_001",
            LicenseErrorMessage.LICENSE_NOT_FOUND
    ),
    ACTIVE_LICENSE_ALREADY_EXISTS(
            HttpStatus.CONFLICT,
            "LICENSE_002",
            LicenseErrorMessage.ACTIVE_LICENSE_ALREADY_EXISTS
    ),
    LICENSE_WAS_MODIFIED(
            HttpStatus.CONFLICT,
            "LICENSE_003",
            LicenseErrorMessage.LICENSE_WAS_MODIFIED
    ),
    INVALID_STATUS_TRANSITION(
            HttpStatus.CONFLICT,
            "LICENSE_004",
            LicenseErrorMessage.INVALID_STATUS_TRANSITION
    ),
    LICENSE_OUTSIDE_EFFECTIVE_PERIOD(
            HttpStatus.CONFLICT,
            "LICENSE_005",
            LicenseErrorMessage.LICENSE_OUTSIDE_EFFECTIVE_PERIOD
    ),
    LICENSE_NOT_EXPIRED(
            HttpStatus.CONFLICT,
            "LICENSE_006",
            LicenseErrorMessage.LICENSE_NOT_EXPIRED
    ),
    LICENSE_NOT_RENEWABLE(
            HttpStatus.CONFLICT,
            "LICENSE_007",
            LicenseErrorMessage.LICENSE_NOT_RENEWABLE
    ),
    LICENSE_NOT_LATEST_PERIOD(
            HttpStatus.CONFLICT,
            "LICENSE_008",
            LicenseErrorMessage.LICENSE_NOT_LATEST_PERIOD
    ),
    LICENSE_ALREADY_RENEWED(
            HttpStatus.CONFLICT,
            "LICENSE_009",
            LicenseErrorMessage.LICENSE_ALREADY_RENEWED
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
