package com.thang.chargeops.booking.controller;

import com.thang.chargeops.booking.dto.filter.OwnerActiveBookingFilter;
import com.thang.chargeops.booking.dto.filter.OwnerBookingFilter;
import com.thang.chargeops.booking.dto.response.*;
import com.thang.chargeops.booking.service.OwnerBookingService;
import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.response.ApiResult;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
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
@RequestMapping(SystemConstant.API_URL_PATTERN + "owner/bookings")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasRole('OWNER')")
public class OwnerBookingController {
    private final OwnerBookingService ownerBookingService;

    @GetMapping
    public ResponseEntity<ApiResult<List<OwnerBookingListItemResponse>>> getOwnerBookings(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) UUID stationId,
            @RequestParam(required = false) UUID connectorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) BookingStatus status) {
        var result = ownerBookingService.getOwnerBookings(
                new OwnerBookingFilter(stationId, connectorId, from, to, status), page, size);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResult.successPage(result));
    }

    @GetMapping("/summary")
    public ResponseEntity<ApiResult<BookingSummaryResponse>> getOwnerBookingSummary(
            @RequestParam(required = false) UUID stationId,
            @RequestParam(required = false) UUID connectorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) BookingStatus status) {
        var result = ownerBookingService.getOwnerBookingSummary(
                new OwnerBookingFilter(stationId, connectorId, from, to, status));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResult.success(result));
    }

    @GetMapping("/active-for")
    public ResponseEntity<ApiResult<List<OperationalBookingResponse>>> getOwnerActiveBookingsFor(
            @RequestParam @NotNull UUID stationId,
            @RequestParam(required = false) UUID chargePointId,
            @RequestParam(required = false) UUID connectorId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var result = ownerBookingService.getOwnerActiveBookingsFor(
                new OwnerActiveBookingFilter(stationId, chargePointId, connectorId), page, size);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResult.successPage(result));
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<ApiResult<OwnerBookingDetailResponse>> getOwnerBooking(@PathVariable UUID bookingId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.success(ownerBookingService.getOwnerBooking(bookingId)));
    }
}
