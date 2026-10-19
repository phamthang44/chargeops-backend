package com.thang.chargeops.booking.export;

import com.thang.chargeops.booking.dto.filter.OwnerBookingFilter;
import com.thang.chargeops.booking.dto.response.BookingCancellationReason;
import com.thang.chargeops.booking.dto.response.OwnerBookingListItemResponse;
import com.thang.chargeops.common.enums.BookingStatus;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class OwnerBookingWorkbookWriterTest {
    private final OwnerBookingWorkbookWriter writer = new OwnerBookingWorkbookWriter();
    private static final Instant NOW = Instant.parse("2026-10-18T17:05:00Z");
    private final OwnerBookingFilter filter = new OwnerBookingFilter(null, null, NOW.minusSeconds(3600), NOW, BookingStatus.CANCELLED);

    private OwnerBookingListItemResponse item() {
        return OwnerBookingListItemResponse.builder().bookingCode("=00123")
                .driverDisplayName("=HYPERLINK(\"https://example.test\")").stationName("Trạm sạc Thủ Đức")
                .connectorCode("0001").status(BookingStatus.CANCELLED).cancellationReason(BookingCancellationReason.NO_SHOW)
                .startAt(NOW).endAt(NOW.plusSeconds(5400)).totalAmount(126000).currency("VND").build();
    }

    @Test
    void createsTypedVietnameseWorkbookWithMatchingSummaryAndInertText() throws Exception {
        byte[] bytes = writer.write(List.of(item(), item()), filter, "startAt,desc", NOW, Locale.forLanguageTag("vi"));
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            var details = workbook.getSheet("Bookings");
            assertThat(details.getLastRowNum()).isEqualTo(2);
            assertThat(details.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Mã booking");
            var row = details.getRow(1);
            assertThat(row.getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("=00123");
            assertThat(row.getCell(1).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(2).getStringCellValue()).isEqualTo("Trạm sạc Thủ Đức");
            assertThat(row.getCell(3).getStringCellValue()).isEqualTo("0001");
            assertThat(DateUtil.isCellDateFormatted(row.getCell(4))).isTrue();
            assertThat(row.getCell(4).getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.of(2026, 10, 19, 0, 5));
            assertThat(row.getCell(6).getNumericCellValue()).isEqualTo(90);
            assertThat(row.getCell(9).getNumericCellValue()).isEqualTo(126000);
            assertThat(row.getCell(8).getStringCellValue()).isEqualTo("NO_SHOW");
            assertThat(details.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) 1);
            assertThat(details.getCTWorksheet().getAutoFilter().getRef()).isEqualTo("A1:K3");
            var summary = workbook.getSheet("Summary");
            assertThat(summary.getRow(2).getCell(1).getStringCellValue()).isEqualTo("Asia/Ho_Chi_Minh");
            assertThat(summary.getRow(10).getCell(1).getNumericCellValue()).isEqualTo(2);
            double statusSum = 0;
            for (int i = 11; i < 11 + BookingStatus.values().length; i++) {
                var bucket = summary.getRow(i);
                statusSum += bucket.getCell(1).getNumericCellValue();
                if (bucket.getCell(0).getStringCellValue().equals("CANCELLED")) {
                    assertThat(bucket.getCell(1).getNumericCellValue()).isEqualTo(2);
                }
            }
            assertThat(statusSum).isEqualTo(2);
        }
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target/owner-bookings-sample.xlsx"), bytes);
    }

    @Test
    void emptyWorkbookStillHasHeadersAndZeroCounts() throws Exception {
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(writer.write(List.of(), filter, "startAt,desc", NOW, Locale.ENGLISH)))) {
            assertThat(workbook.getSheet("Bookings").getLastRowNum()).isZero();
            assertThat(workbook.getSheet("Bookings").getRow(0).getCell(0).getStringCellValue()).isEqualTo("Booking code");
            assertThat(workbook.getSheet("Summary").getRow(10).getCell(1).getNumericCellValue()).isZero();
        }
    }

    @Test
    void supportsMaximumTenThousandRows() throws Exception {
        byte[] bytes = writer.write(Collections.nCopies(10_000, item()), filter, "startAt,desc", NOW, Locale.ENGLISH);
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getSheet("Bookings").getLastRowNum()).isEqualTo(10_000);
            assertThat(workbook.getSheet("Summary").getRow(10).getCell(1).getNumericCellValue()).isEqualTo(10_000);
        }
    }
}
