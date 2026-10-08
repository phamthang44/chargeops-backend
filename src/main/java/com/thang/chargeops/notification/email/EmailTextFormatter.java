package com.thang.chargeops.notification.email;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * Server-side text formatting for email content (document #56 §7): times are
 * rendered in Asia/Ho_Chi_Minh, money in whole VND, links from FRONTEND_BASE_URL.
 * Templates stay markup-only — no formatting logic lives in Thymeleaf.
 */
public final class EmailTextFormatter {

    public static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final Locale DISPLAY_LOCALE = Locale.forLanguageTag("vi-VN");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(DISPLAY_ZONE);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(DISPLAY_ZONE);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(DISPLAY_ZONE);

    private EmailTextFormatter() {
    }

    /** 126000 → "126.000 ₫" (vi-VN grouping, whole VND). */
    public static String formatVnd(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("amount is required");
        }
        NumberFormat format = NumberFormat.getInstance(DISPLAY_LOCALE);
        format.setMaximumFractionDigits(0);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(amount) + " ₫";
    }

    public static String formatDateTime(Instant instant) {
        if (instant == null) {
            throw new IllegalArgumentException("instant is required");
        }
        return DATE_TIME.format(instant);
    }

    /** Same day → "14/10/2026 · 09:00 – 10:30", otherwise both full dates. */
    public static String formatTimeWindow(Instant start, Instant end) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("start and end are required");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start");
        }
        LocalDate startDate = start.atZone(DISPLAY_ZONE).toLocalDate();
        LocalDate endDate = end.atZone(DISPLAY_ZONE).toLocalDate();
        if (startDate.equals(endDate)) {
            return DATE.format(start) + " · " + TIME.format(start) + " – " + TIME.format(end);
        }
        return DATE_TIME.format(start) + " – " + DATE_TIME.format(end);
    }

    public static String buildBookingUrl(String frontendUrl, UUID bookingId) {
        if (bookingId == null) {
            throw new IllegalArgumentException("bookingId is required");
        }
        if (frontendUrl == null || frontendUrl.isBlank()) {
            throw new IllegalArgumentException("frontendUrl is required");
        }
        String base = frontendUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + NotificationEmailConstants.BOOKING_PATH + bookingId;
    }

    /** Never greet with a blank name; the email local part is the last fallback. */
    public static String safeRecipientName(String displayName, String email) {
        if (displayName != null && !displayName.isBlank()) {
            return displayName.trim();
        }
        if (email != null && !email.isBlank()) {
            String local = email.substring(0, email.indexOf('@') > 0 ? email.indexOf('@') : email.length()).trim();
            if (!local.isEmpty()) {
                return local;
            }
        }
        return "bạn";
    }
}
