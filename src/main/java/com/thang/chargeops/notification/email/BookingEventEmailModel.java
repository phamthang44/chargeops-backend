package com.thang.chargeops.notification.email;

/** Only safe display fields, resolved from authoritative source and current recipient scope. */
public record BookingEventEmailModel(String recipientName, String recipientEmail, String bookingCode,
        String stationName, String timeWindowText, String occurredAtText, String heading,
        String message, String actionUrl, String supportContact, String preheader) {
    public String subjectLine() { return "ChargeOps · " + heading + " · " + bookingCode; }
}
