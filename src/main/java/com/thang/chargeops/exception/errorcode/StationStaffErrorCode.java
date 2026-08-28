package com.thang.chargeops.exception.errorcode;

import com.thang.chargeops.exception.errormessage.ErrorMessage;
import com.thang.chargeops.exception.errormessage.StationStaffErrorMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum StationStaffErrorCode implements BaseErrorCode {

    CANDIDATE_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "STATION_STAFF_001",
            StationStaffErrorMessage.CANDIDATE_NOT_FOUND
    ),
    STATION_NOT_ACTIVE_FOR_ASSIGNMENT(
            HttpStatus.CONFLICT,
            "STATION_STAFF_002",
            StationStaffErrorMessage.STATION_NOT_ACTIVE_FOR_ASSIGNMENT
    ),
    SELF_ASSIGNMENT_NOT_ALLOWED(
            HttpStatus.CONFLICT,
            "STATION_STAFF_003",
            StationStaffErrorMessage.SELF_ASSIGNMENT_NOT_ALLOWED
    ),
    CANDIDATE_ACCOUNT_INACTIVE(
            HttpStatus.CONFLICT,
            "STATION_STAFF_004",
            StationStaffErrorMessage.CANDIDATE_ACCOUNT_INACTIVE
    ),
    CANDIDATE_ROLE_NOT_ALLOWED(
            HttpStatus.CONFLICT,
            "STATION_STAFF_005",
            StationStaffErrorMessage.CANDIDATE_ROLE_NOT_ALLOWED
    ),
    ACTIVE_ASSIGNMENT_ALREADY_EXISTS(
            HttpStatus.CONFLICT,
            "STATION_STAFF_006",
            StationStaffErrorMessage.ACTIVE_ASSIGNMENT_ALREADY_EXISTS
    ),
    ASSIGNMENT_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "STATION_STAFF_007",
            StationStaffErrorMessage.ASSIGNMENT_NOT_FOUND
    ),
    ASSIGNMENT_ALREADY_REVOKED(
            HttpStatus.CONFLICT,
            "STATION_STAFF_008",
            StationStaffErrorMessage.ASSIGNMENT_ALREADY_REVOKED
    ),
    IDENTITY_PROVIDER_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "STATION_STAFF_009",
            StationStaffErrorMessage.IDENTITY_PROVIDER_UNAVAILABLE
    ),
    IDENTITY_ROLE_OPERATION_FAILED(
            HttpStatus.BAD_GATEWAY,
            "STATION_STAFF_010",
            StationStaffErrorMessage.IDENTITY_ROLE_OPERATION_FAILED
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
