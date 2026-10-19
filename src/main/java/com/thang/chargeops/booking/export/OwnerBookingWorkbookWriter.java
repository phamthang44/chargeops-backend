package com.thang.chargeops.booking.export;

import com.thang.chargeops.booking.dto.filter.OwnerBookingFilter;
import com.thang.chargeops.booking.dto.response.OwnerBookingListItemResponse;
import com.thang.chargeops.common.enums.BookingStatus;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

@Component
public class OwnerBookingWorkbookWriter {
    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final String MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    public byte[] write(List<OwnerBookingListItemResponse> items, OwnerBookingFilter filter,
                        String sort, Instant evaluatedAt, Locale locale) {
        boolean vi = "vi".equals(locale.getLanguage());
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var headerStyle = workbook.createCellStyle();
            var font = workbook.createFont();
            font.setBold(true);
            font.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(font);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("dd/mm/yyyy hh:mm:ss"));
            var numberStyle = workbook.createCellStyle();
            numberStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0.##"));

            var details = workbook.createSheet("Bookings");
            String[] headings = vi
                    ? new String[]{"Mã booking", "Tài xế", "Trạm", "Đầu sạc", "Bắt đầu", "Kết thúc",
                    "Thời lượng đặt chỗ (phút)", "Trạng thái", "Lý do hủy", "Giá trị booking", "Đơn vị tiền"}
                    : new String[]{"Booking code", "Driver", "Station", "Connector", "Start", "End",
                    "Booked duration (minutes)", "Status", "Cancellation reason", "Booking value", "Currency"};
            var header = details.createRow(0);
            int[] widths = {25, 30, 35, 24, 24, 24, 29, 20, 28, 23, 16};
            for (int i = 0; i < headings.length; i++) {
                text(header, i, headings[i]);
                header.getCell(i).setCellStyle(headerStyle);
                details.setColumnWidth(i, widths[i] * 256);
            }
            for (var item : items) {
                var row = details.createRow(details.getLastRowNum() + 1);
                text(row, 0, item.bookingCode()); text(row, 1, item.driverDisplayName());
                text(row, 2, item.stationName()); text(row, 3, item.connectorCode());
                date(row, 4, item.startAt(), dateStyle); date(row, 5, item.endAt(), dateStyle);
                number(row, 6, Duration.between(item.startAt(), item.endAt()).toSeconds() / 60.0, numberStyle);
                text(row, 7, item.status().name()); text(row, 8, item.cancellationReason());
                number(row, 9, item.totalAmount(), numberStyle); text(row, 10, item.currency());
            }
            details.createFreezePane(0, 1);
            details.setAutoFilter(new CellRangeAddress(0, details.getLastRowNum(), 0, headings.length - 1));

            var summary = workbook.createSheet("Summary");
            summary.setColumnWidth(0, 35 * 256); summary.setColumnWidth(1, 100 * 256);
            var summaryHeader = summary.createRow(0);
            text(summaryHeader, 0, vi ? "Thông tin" : "Metric");
            text(summaryHeader, 1, vi ? "Giá trị" : "Value");
            summaryHeader.forEach(cell -> cell.setCellStyle(headerStyle));
            var generated = summary.createRow(1);
            text(generated, 0, vi ? "Thời điểm xuất" : "Generated at");
            date(generated, 1, evaluatedAt, dateStyle);
            pair(summary, vi ? "Múi giờ" : "Timezone", ZONE.getId());
            pair(summary, "stationId", filter.stationId()); pair(summary, "connectorId", filter.connectorId());
            filterDate(summary, "from", filter.from(), dateStyle);
            filterDate(summary, "to (exclusive)", filter.to(), dateStyle);
            pair(summary, "status", filter.status()); pair(summary, "windowMode", filter.windowMode());
            pair(summary, "sort", sort);
            count(summary, vi ? "Tổng số booking" : "Total bookings", items.size());
            for (var status : BookingStatus.values()) {
                count(summary, status.name(), items.stream().filter(item -> item.status() == status).count());
            }
            pair(summary, vi ? "Ghi chú" : "Note", vi
                    ? "Giá trị booking không phải doanh thu thực thu. Thời lượng là thời gian đặt chỗ, không phải thời gian sạc thực tế."
                    : "Booking value is not collected revenue. Duration is the booked time, not actual charging time.");
            summary.createFreezePane(0, 1);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not generate booking workbook", exception);
        }
    }

    private static void text(Row row, int column, Object value) {
        // Explicit string cells keep formula-looking user content inert.
        row.createCell(column, CellType.STRING).setCellValue(value == null ? "" : value.toString());
    }

    private static void number(Row row, int column, double value, CellStyle style) {
        var cell = row.createCell(column, CellType.NUMERIC);
        cell.setCellValue(value); cell.setCellStyle(style);
    }

    private static void date(Row row, int column, Instant value, CellStyle style) {
        var cell = row.createCell(column);
        if (value != null) cell.setCellValue(value.atZone(ZONE).toLocalDateTime());
        cell.setCellStyle(style);
    }

    private static void pair(Sheet sheet, String key, Object value) {
        var row = sheet.createRow(sheet.getLastRowNum() + 1);
        text(row, 0, key); text(row, 1, value);
    }

    private static void count(Sheet sheet, String key, long value) {
        var row = sheet.createRow(sheet.getLastRowNum() + 1);
        text(row, 0, key); row.createCell(1, CellType.NUMERIC).setCellValue(value);
    }

    private static void filterDate(Sheet sheet, String key, Instant value, CellStyle style) {
        var row = sheet.createRow(sheet.getLastRowNum() + 1);
        text(row, 0, key); date(row, 1, value, style);
    }
}
