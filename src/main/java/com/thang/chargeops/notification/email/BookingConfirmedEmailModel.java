package com.thang.chargeops.notification.email;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Fixed input contract for the booking-confirmed email template.
 *
 * <p>Every field is display-ready text formatted server-side in vi-VN / Asia-Ho_Chi_Minh
 * (document #56 §7). The template contains no logic — it only prints these fields, so the
 * template and the renderer cannot drift apart. Raw {@code paidAmount}/{@code currency}
 * are kept for tests and future reuse. No JPA entity, receipt or bank reference is exposed.
 */
public record BookingConfirmedEmailModel(
        String recipientName,
        String recipientEmail,
        String bookingCode,
        UUID bookingId,
        String stationName,
        String stationAddress,
        String chargePointCode,
        String connectorCode,
        String startAtText,
        String endAtText,
        String timeWindowText,
        String timeZoneLabel,
        String paidAmountText,
        BigDecimal paidAmount,
        String currency,
        String bookingUrl,
        String supportContact,
        String preheader) {

    public BookingConfirmedEmailModel {
        recipientName = requireText(recipientName, "recipientName");
        bookingCode = requireText(bookingCode, "bookingCode");
        Objects.requireNonNull(bookingId, "bookingId is required");
        stationName = requireText(stationName, "stationName");
        stationAddress = requireText(stationAddress, "stationAddress");
        chargePointCode = requireText(chargePointCode, "chargePointCode");
        connectorCode = requireText(connectorCode, "connectorCode");
        startAtText = requireText(startAtText, "startAtText");
        endAtText = requireText(endAtText, "endAtText");
        timeWindowText = requireText(timeWindowText, "timeWindowText");
        timeZoneLabel = requireText(timeZoneLabel, "timeZoneLabel");
        paidAmountText = requireText(paidAmountText, "paidAmountText");
        Objects.requireNonNull(paidAmount, "paidAmount is required");
        if (paidAmount.signum() < 0) {
            throw new IllegalArgumentException("paidAmount must not be negative");
        }
        currency = requireText(currency, "currency");
        bookingUrl = requireText(bookingUrl, "bookingUrl");
        preheader = requireText(preheader, "preheader");
        if (recipientEmail != null && recipientEmail.isBlank()) {
            recipientEmail = null;
        }
        if (supportContact != null && supportContact.isBlank()) {
            supportContact = null;
        }
    }

    /**
     * Single entry point for callers: takes the domain snapshot as raw values and returns a
     * model whose fields are already formatted for display.
     */
    public static BookingConfirmedEmailModel confirmed(
            String recipientName,
            String recipientEmail,
            String bookingCode,
            UUID bookingId,
            String stationName,
            String stationAddress,
            String chargePointCode,
            String connectorCode,
            Instant startAt,
            Instant endAt,
            BigDecimal paidAmount,
            String currency,
            String frontendUrl,
            String supportContact) {

        Objects.requireNonNull(startAt, "startAt is required");
        Objects.requireNonNull(endAt, "endAt is required");
        String code = requireText(bookingCode, "bookingCode");
        String timeWindow = EmailTextFormatter.formatTimeWindow(startAt, endAt);
        String paidAmountText = EmailTextFormatter.formatVnd(paidAmount);
        String preheader = "Booking " + code + " đã xác nhận · " + timeWindow + " · " + paidAmountText;

        return new BookingConfirmedEmailModel(
                EmailTextFormatter.safeRecipientName(recipientName, recipientEmail),
                recipientEmail,
                code,
                bookingId,
                requireText(stationName, "stationName"),
                requireText(stationAddress, "stationAddress"),
                requireText(chargePointCode, "chargePointCode"),
                requireText(connectorCode, "connectorCode"),
                EmailTextFormatter.formatDateTime(startAt),
                EmailTextFormatter.formatDateTime(endAt),
                timeWindow,
                NotificationEmailConstants.TIME_ZONE_LABEL,
                paidAmountText,
                paidAmount,
                requireText(currency, "currency"),
                EmailTextFormatter.buildBookingUrl(frontendUrl, bookingId),
                supportContact,
                preheader);
    }

    /** Preheader shown as the inbox preview line. */
    public String preheaderLine() {
        return preheader;
    }

    public String greetingName() {
        return recipientName;
    }

    public String fullTimeLine() {
        return timeWindowText + " (" + timeZoneLabel + ")";
    }

    public String subjectLine() {
        return String.format(Locale.forLanguageTag("vi-VN"), "ChargeOps · Đã xác nhận booking %s", bookingCode);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
