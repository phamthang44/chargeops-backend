package com.thang.chargeops.booking.checkin;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;

public class InvalidCheckInChallengeException extends AppException {

    public InvalidCheckInChallengeException() {
        super(StationErrorCode.INVALID_CHECK_IN_CHALLENGE);
    }

    public InvalidCheckInChallengeException(String message) {
        super(StationErrorCode.INVALID_CHECK_IN_CHALLENGE);
    }
}
