package com.thang.chargeops.notification.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Renders the booking-confirmed template with fake data into target/email-preview so the
 * design can be reviewed in a browser before any SMTP/Kafka wiring exists (document #56 §7).
 *
 * Run: mvn -Dtest=BookingConfirmedEmailPreviewTest test
 * Open: target/email-preview/booking-confirmed.html
 */
class BookingConfirmedEmailPreviewTest {

    private static final UUID BOOKING_ID = UUID.fromString("6f0a1c3e-4b2d-4e8a-9c1f-2a7d5e9b3c44");
    private static final String FRONTEND_URL = "http://localhost:5173";

    private BookingConfirmedEmailModel sampleModel() {
        return BookingConfirmedEmailModel.confirmed(
                "Nguyễn Văn An",
                "an.nguyen@example.com",
                "BK-2026-000482",
                BOOKING_ID,
                "Trạm ChargeOps Vinhomes Central Park",
                "208 Nguyễn Hữu Cảnh, Phường 22, Bình Thạnh, TP. Hồ Chí Minh",
                "CP-07",
                "C2 · CCS2 60 kW",
                LocalDateTime.of(2026, 10, 14, 9, 0).toInstant(ZoneOffset.ofHours(7)),
                LocalDateTime.of(2026, 10, 14, 10, 30).toInstant(ZoneOffset.ofHours(7)),
                new BigDecimal("126000.00"),
                "VND",
                FRONTEND_URL,
                "hotro@chargeops.vn");
    }

    @Test
    @DisplayName("Renders HTML + plain text preview files with Vietnamese content")
    void rendersPreviewFiles() throws IOException {
        BookingConfirmedEmailModel model = sampleModel();
        BookingEmailRenderer.RenderedEmail email = new BookingEmailRenderer(false).renderBookingConfirmed(model);

        Path outputDir = Path.of("target", "email-preview");
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("booking-confirmed.html"), email.html(), StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("booking-confirmed.txt"), email.text(), StandardCharsets.UTF_8);

        String html = email.html();
        String text = email.text();

        assertThat(email.subject()).isEqualTo("ChargeOps · Đã xác nhận booking BK-2026-000482");
        assertThat(html).startsWith("<!DOCTYPE html>").contains("lang=\"vi\"");
        assertThat(html).doesNotContainPattern("(^|\\s)th:[a-zA-Z]+=")
                .as("no unprocessed Thymeleaf attributes may leak into the email");
        assertThat(html)
                .contains("Nguyễn Văn An")
                .contains("BK-2026-000482")
                .contains("Trạm ChargeOps Vinhomes Central Park")
                .contains("208 Nguyễn Hữu Cảnh")
                .contains("Trụ CP-07 · Cổng C2 · CCS2 60 kW")
                .contains("14/10/2026 · 09:00 – 10:30")
                .contains("Giờ Việt Nam (GMT+7)")
                .contains("126.000 ₫")
                .contains("ứng dụng di động ChargeOps")
                .contains("hotro@chargeops.vn");
        assertThat(html)
                .contains("Booking BK-2026-000482 đã xác nhận")
                .as("preheader must be present for the inbox preview line");
        assertThat(text)
                .contains("BK-2026-000482")
                .contains("126.000 ₫")
                .contains("ứng dụng ChargeOps")
                .doesNotContain("<");
    }

    @Test
    @DisplayName("Missing domain data is rejected before rendering")
    void rejectsIncompleteModel() {
        assertThatThrownBy(() -> BookingConfirmedEmailModel.confirmed(
                "Nguyễn Văn An", "an@example.com", " ", BOOKING_ID,
                "Trạm A", "Địa chỉ A", "CP-01", "C1",
                Instant.parse("2026-10-14T02:00:00Z"), Instant.parse("2026-10-14T03:00:00Z"),
                BigDecimal.TEN, "VND", FRONTEND_URL, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bookingCode");
    }
}
