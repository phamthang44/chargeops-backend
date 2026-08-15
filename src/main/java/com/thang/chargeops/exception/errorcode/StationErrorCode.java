package com.thang.chargeops.exception.errorcode;

import com.thang.chargeops.exception.errormessage.ErrorMessage;
import com.thang.chargeops.exception.errormessage.StationErrorMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum StationErrorCode implements BaseErrorCode {

    STATION_NOT_FOUND(
            HttpStatus.NOT_FOUND, "STATION_001", StationErrorMessage.STATION_NOT_FOUND),
    STATION_ACCESS_DENIED(
            HttpStatus.FORBIDDEN, "STATION_002", StationErrorMessage.STATION_ACCESS_DENIED),
    INVALID_STATUS_TRANSITION(
            HttpStatus.CONFLICT, "STATION_003", StationErrorMessage.INVALID_STATUS_TRANSITION),
    ACTIVE_LICENSE_ALREADY_EXISTS(
            HttpStatus.CONFLICT, "STATION_005", StationErrorMessage.ACTIVE_LICENSE_ALREADY_EXISTS),
    LICENSE_NOT_FOUND(
            HttpStatus.NOT_FOUND, "STATION_006", StationErrorMessage.LICENSE_NOT_FOUND),
    CHARGE_POINT_NOT_FOUND(
            HttpStatus.NOT_FOUND, "STATION_007", StationErrorMessage.CHARGE_POINT_NOT_FOUND),
    CHARGE_POINT_CODE_ALREADY_EXISTS(
            HttpStatus.CONFLICT, "STATION_008", StationErrorMessage.CHARGE_POINT_CODE_ALREADY_EXISTS),
    CONNECTOR_NOT_FOUND(
            HttpStatus.NOT_FOUND, "STATION_009", StationErrorMessage.CONNECTOR_NOT_FOUND),
    CONNECTOR_CODE_ALREADY_EXISTS(
            HttpStatus.CONFLICT, "STATION_010", StationErrorMessage.CONNECTOR_CODE_ALREADY_EXISTS),
    STATION_SUSPENSION_REASON_REQUIRED(
            HttpStatus.BAD_REQUEST, "STATION_012", StationErrorMessage.STATION_SUSPENSION_REASON_REQUIRED);

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
