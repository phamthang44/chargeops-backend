package com.thang.chargeops.exception.errorcode;

import com.thang.chargeops.exception.errormessage.BookingErrorMessage;
import com.thang.chargeops.exception.errormessage.ErrorMessage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum BookingErrorCode implements BaseErrorCode {
    TIME_INVALID(HttpStatus.BAD_REQUEST, "BKG_TIME_INVALID", BookingErrorMessage.TIME_INVALID),
    SLOT_UNAVAILABLE(HttpStatus.CONFLICT, "BKG_SLOT_UNAVAILABLE", BookingErrorMessage.SLOT_UNAVAILABLE),
    PRICE_CHANGED(HttpStatus.CONFLICT, "BKG_PRICE_CHANGED", BookingErrorMessage.PRICE_CHANGED),
    PRICING_NOT_CONFIGURED(HttpStatus.CONFLICT, "BKG_PRICING_NOT_CONFIGURED", BookingErrorMessage.PRICING_NOT_CONFIGURED),
    CANCELLATION_CHANGED(HttpStatus.CONFLICT, "BKG_CANCELLATION_CHANGED", BookingErrorMessage.CANCELLATION_CHANGED),
    STATE_CONFLICT(HttpStatus.CONFLICT, "BKG_STATE_CONFLICT", BookingErrorMessage.STATE_CONFLICT),
    HOLD_EXPIRED(HttpStatus.CONFLICT, "BKG_HOLD_EXPIRED", BookingErrorMessage.HOLD_EXPIRED),
    CHECK_IN_TOO_EARLY(HttpStatus.CONFLICT, "BKG_CHECK_IN_TOO_EARLY", BookingErrorMessage.CHECK_IN_TOO_EARLY),
    CHECK_IN_CLOSED(HttpStatus.CONFLICT, "BKG_CHECK_IN_CLOSED", BookingErrorMessage.CHECK_IN_CLOSED),
    QR_INVALID(HttpStatus.CONFLICT, "BKG_QR_INVALID", BookingErrorMessage.QR_INVALID),
    CONNECTOR_MISMATCH(HttpStatus.CONFLICT, "BKG_CONNECTOR_MISMATCH", BookingErrorMessage.CONNECTOR_MISMATCH),
    STATION_UNAVAILABLE(HttpStatus.CONFLICT, "BKG_STATION_UNAVAILABLE", BookingErrorMessage.STATION_UNAVAILABLE),
    PENDING_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "BKG_PENDING_LIMIT_EXCEEDED", BookingErrorMessage.PENDING_LIMIT_EXCEEDED),
    BOOKING_NOT_ACCESS(HttpStatus.FORBIDDEN, "BKG_NOT_ACCESS", BookingErrorMessage.BOOKING_NOT_ACCESS);

    private final HttpStatus httpStatus;
    private final String code;
    private final ErrorMessage.Template template;

    @Override public String getMessageKey() { return template.key(); }
    @Override public String getMessage() { return template.defaultMessage(); }
}
