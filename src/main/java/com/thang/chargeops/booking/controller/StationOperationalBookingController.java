package com.thang.chargeops.booking.controller;

import com.thang.chargeops.booking.dto.filter.StationOperationalBookingFilter;
import com.thang.chargeops.booking.dto.response.OperationalBookingResponse;
import com.thang.chargeops.booking.service.StationOperationalBookingService;
import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "stations/{stationId}/bookings")
@RequiredArgsConstructor
@Validated
@PreAuthorize("isAuthenticated()")
public class StationOperationalBookingController {

    private final StationOperationalBookingService stationOperationalBookingService;

    @GetMapping
    public ResponseEntity<ApiResult<List<OperationalBookingResponse>>> getOperationalBookings(
            @PathVariable UUID stationId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) UUID connectorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        var result = stationOperationalBookingService.getOperationalBookings(
                stationId,
                new StationOperationalBookingFilter(connectorId, from, to),
                page,
                size
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResult.successPage(result));
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<ApiResult<OperationalBookingResponse>> getOperationalBooking(
            @PathVariable UUID stationId,
            @PathVariable UUID bookingId) {
        var result = stationOperationalBookingService.getOperationalBooking(stationId, bookingId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResult.success(result));
    }
}
