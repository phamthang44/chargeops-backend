package com.thang.chargeops.booking.export;

import com.thang.chargeops.exception.errorcode.BaseErrorCode;
import org.springframework.http.HttpStatus;

public enum OwnerBookingExportErrorCode implements BaseErrorCode {
    LIMIT_EXCEEDED;

    @Override public String getCode() { return "BKG_EXPORT_LIMIT_EXCEEDED"; }
    @Override public String getMessageKey() { return "error.booking.exportLimitExceeded"; }
    @Override public String getMessage() {
        return "Export is limited to 10,000 bookings. Narrow the station, time or status filters and try again.";
    }
    @Override public HttpStatus getHttpStatus() { return HttpStatus.BAD_REQUEST; }
}
