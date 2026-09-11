package com.thang.chargeops.booking.exception;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.BaseErrorCode;

public class BookingDomainException extends AppException {
    public BookingDomainException(BaseErrorCode errorCode, Object... args) {
        super(errorCode, args);
    }

    public BookingDomainException(BaseErrorCode errorCode, Object details, Object[] args) {
        super(errorCode, details, args);
    }
}
