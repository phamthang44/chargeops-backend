package com.thang.chargeops.notification.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmailTextFormatterTest {

    @Test
    @DisplayName("VND formats with vi-VN grouping and the ₫ symbol")
    void formatsVnd() {
        assertThat(EmailTextFormatter.formatVnd(new BigDecimal("126000.00"))).isEqualTo("126.000 ₫");
        assertThat(EmailTextFormatter.formatVnd(BigDecimal.ZERO)).isEqualTo("0 ₫");
        assertThatThrownBy(() -> EmailTextFormatter.formatVnd(null))
                .as("amount is mandatory")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
    }

    @Test
    @DisplayName("Time window renders Asia/Ho_Chi_Minh and collapses to one date on the same day")
    void formatsTimeWindow() {
        Instant start = LocalDateTime.of(2026, 10, 14, 9, 0).toInstant(ZoneOffset.ofHours(7));
        Instant end = LocalDateTime.of(2026, 10, 14, 10, 30).toInstant(ZoneOffset.ofHours(7));
        assertThat(EmailTextFormatter.formatTimeWindow(start, end)).isEqualTo("14/10/2026 · 09:00 – 10:30");

        Instant overnightEnd = LocalDateTime.of(2026, 10, 15, 6, 30).toInstant(ZoneOffset.ofHours(7));
        assertThat(EmailTextFormatter.formatTimeWindow(start, overnightEnd))
                .isEqualTo("14/10/2026 09:00 – 15/10/2026 06:30");
    }

    @Test
    @DisplayName("Booking URL joins FRONTEND_BASE_URL and the inbox booking path")
    void buildsBookingUrl() {
        UUID bookingId = UUID.fromString("6f0a1c3e-4b2d-4e8a-9c1f-2a7d5e9b3c44");
        assertThat(EmailTextFormatter.buildBookingUrl("http://localhost:5173/", bookingId))
                .isEqualTo("http://localhost:5173/bookings/" + bookingId);
        assertThat(EmailTextFormatter.buildBookingUrl("https://app.chargeops.vn", bookingId))
                .isEqualTo("https://app.chargeops.vn/bookings/" + bookingId);
        assertThatThrownBy(() -> EmailTextFormatter.buildBookingUrl("  ", bookingId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Greeting name falls back to the email local part, never blank")
    void safeRecipientName() {
        assertThat(EmailTextFormatter.safeRecipientName("Nguyễn Văn An", "an@example.com")).isEqualTo("Nguyễn Văn An");
        assertThat(EmailTextFormatter.safeRecipientName("  ", "an.nguyen@example.com")).isEqualTo("an.nguyen");
        assertThat(EmailTextFormatter.safeRecipientName(null, null)).isEqualTo("bạn");
    }
}
