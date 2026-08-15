package com.thang.chargeops.exception.errorcode;

import com.thang.chargeops.exception.errormessage.AdministrativeLocationErrorMessage;
import com.thang.chargeops.exception.errormessage.ErrorMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum AdministrativeLocationErrorCode implements BaseErrorCode {

    PROVINCE_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "LOCATION_001",
            AdministrativeLocationErrorMessage.PROVINCE_NOT_FOUND
    ),
    WARD_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "LOCATION_002",
            AdministrativeLocationErrorMessage.WARD_NOT_FOUND
    ),
    WARD_PROVINCE_MISMATCH(
            HttpStatus.BAD_REQUEST,
            "LOCATION_003",
            AdministrativeLocationErrorMessage.WARD_PROVINCE_MISMATCH
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
