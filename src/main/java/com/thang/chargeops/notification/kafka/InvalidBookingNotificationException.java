package com.thang.chargeops.notification.kafka;

/** Controlled reason only: never include the received payload or personal data in an exception. */
public class InvalidBookingNotificationException extends IllegalArgumentException {
    public InvalidBookingNotificationException(String code) {
        super(code);
    }
}
