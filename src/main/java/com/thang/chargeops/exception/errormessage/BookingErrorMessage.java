package com.thang.chargeops.exception.errormessage;

import java.util.List;

import static com.thang.chargeops.exception.errormessage.ErrorMessage.template;

public final class BookingErrorMessage {
    private BookingErrorMessage() {
    }

    public static final ErrorMessage.Template TIME_INVALID = template("error.booking.timeInvalid", "The booking time is invalid");
    public static final ErrorMessage.Template SLOT_UNAVAILABLE = template("error.booking.slotUnavailable", "The selected slot is unavailable");
    public static final ErrorMessage.Template PRICE_CHANGED = template("error.booking.priceChanged", "The booking price changed; review the latest preview");
    public static final ErrorMessage.Template PRICING_NOT_CONFIGURED = template("error.booking.pricingNotConfigured", "Booking pricing is not configured");
    public static final ErrorMessage.Template CANCELLATION_CHANGED = template("error.booking.cancellationChanged", "The cancellation terms changed; review the booking again");
    public static final ErrorMessage.Template STATE_CONFLICT = template("error.booking.stateConflict", "The booking is no longer in the expected state");
    public static final ErrorMessage.Template HOLD_EXPIRED = template("error.booking.holdExpired", "The payment hold has expired");
    public static final ErrorMessage.Template CHECK_IN_TOO_EARLY = template("error.booking.checkInTooEarly", "Check-in is not open yet");
    public static final ErrorMessage.Template CHECK_IN_CLOSED = template("error.booking.checkInClosed", "The check-in window is closed");
    public static final ErrorMessage.Template QR_INVALID = template("error.booking.qrInvalid", "The QR code is invalid or expired");
    public static final ErrorMessage.Template CONNECTOR_MISMATCH = template("error.booking.connectorMismatch", "The QR code does not match this connector");
    public static final ErrorMessage.Template STATION_UNAVAILABLE = template("error.booking.stationUnavailable", "The station is unavailable");
    public static final ErrorMessage.Template PENDING_LIMIT_EXCEEDED = template("error.booking.pendingLimitExceeded", "The driver already has the maximum number of pending bookings");
    public static final ErrorMessage.Template BOOKING_NOT_ACCESS = template("error.booking.notAccess", "You do not have access to this booking");

    static List<ErrorMessage.Template> templates() {
        return List.of(TIME_INVALID, SLOT_UNAVAILABLE, PRICE_CHANGED, PRICING_NOT_CONFIGURED,
                CANCELLATION_CHANGED, STATE_CONFLICT, HOLD_EXPIRED, CHECK_IN_TOO_EARLY,
                CHECK_IN_CLOSED, QR_INVALID, CONNECTOR_MISMATCH, STATION_UNAVAILABLE,
                PENDING_LIMIT_EXCEEDED, BOOKING_NOT_ACCESS);
    }
}
