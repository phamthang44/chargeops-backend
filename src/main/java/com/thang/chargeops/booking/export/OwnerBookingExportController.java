package com.thang.chargeops.booking.export;

import com.thang.chargeops.booking.dto.filter.BookingWindowMode;
import com.thang.chargeops.booking.dto.filter.OwnerBookingFilter;
import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.enums.BookingStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "owner/bookings")
@RequiredArgsConstructor
@PreAuthorize("hasRole('OWNER')")
public class OwnerBookingExportController {
    private final OwnerBookingExportService exportService;

    // Do not restrict produces: validation/business failures must still negotiate JSON.
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportBookings(
            @RequestParam(required = false) UUID stationId,
            @RequestParam(required = false) UUID connectorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) BookingStatus status,
            @RequestParam(defaultValue = "OVERLAP") BookingWindowMode windowMode,
            @RequestParam(defaultValue = "startAt,desc") String sort,
            Locale locale) {
        var file = exportService.export(new OwnerBookingFilter(stationId, connectorId, from, to, status, windowMode), sort, locale);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(OwnerBookingWorkbookWriter.MIME))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.filename()).build().toString())
                .body(file.content());
    }
}
