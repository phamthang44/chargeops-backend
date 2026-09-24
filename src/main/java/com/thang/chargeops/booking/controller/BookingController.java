package com.thang.chargeops.booking.controller;


import com.thang.chargeops.booking.dto.filter.DriverBookingHistoryFilter;
import com.thang.chargeops.booking.dto.request.CreateBookingRequest;
import com.thang.chargeops.booking.dto.request.PricePreviewRequest;
import com.thang.chargeops.booking.dto.response.*;
import com.thang.chargeops.booking.service.BookingPricingService;
import com.thang.chargeops.booking.service.BookingService;
import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingPricingService bookingPricingService;
    private final BookingService bookingService;
    private final com.thang.chargeops.booking.service.BookingCancellationService bookingCancellationService;

    private static final String CACHE_CONTROL_NO_STORE = "no-store";

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/price-preview")
    public ResponseEntity<ApiResult<PricePreviewResponse>> previewBookingPrice(
            @Valid @RequestBody PricePreviewRequest request
    ) {
        PricePreviewResponse response = bookingPricingService
                .previewBookingPrice(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_NO_STORE)
                .body(ApiResult.success(response));
    }

    @PostMapping
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<ApiResult<CreateBookingResponse>> createBooking(
            @RequestHeader("Idempotency-Key") UUID requestKey,
            @Valid @RequestBody CreateBookingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success(
                        bookingService.createNewBooking(requestKey, request)
                ));
    }

    @PostMapping("/{bookingId}/checkout")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<ApiResult<CheckoutResponse>> createCheckout(
            @PathVariable UUID bookingId,
            @RequestHeader("Idempotency-Key") UUID requestKey
    ) {
        return ResponseEntity.ok(ApiResult.success(
                bookingService.createCheckout(bookingId, requestKey)
        ));
    }

    @PostMapping("/{bookingId}/cancel")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<ApiResult<BookingDetailResponse>> cancelBooking(
            @PathVariable UUID bookingId,
            @RequestHeader("Idempotency-Key") UUID requestKey,
            @Valid @RequestBody com.thang.chargeops.booking.dto.request.CancelBookingRequest request
    ) {
        BookingDetailResponse response = bookingCancellationService
                .cancelBooking(bookingId, requestKey, request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_NO_STORE)
                .body(ApiResult.success(response));
    }

    @GetMapping("/active")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<ApiResult<?>> getMyActiveBookings(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size

    ) {
        var response = bookingService.getMyActiveBookings(page, size);

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_NO_STORE)
                .body(ApiResult.successPage(response));
    }

    @GetMapping("/history")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<ApiResult<List<DriverBookingListItemResponse>>>
    getMyBookingHistory(
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "ALL")
            DriverBookingHistoryFilter.HistoryStatus status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        var result = bookingService
                .getMyBookingHistory(
                        new DriverBookingHistoryFilter(query, status),
                        page,
                        size
                );

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_NO_STORE)
                .body(ApiResult.successPage(
                        result.page(),
                        result.counts()
                ));
    }

    @GetMapping("/{bookingId}")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<ApiResult<BookingDetailResponse>> getBookingDetail(@PathVariable UUID bookingId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_NO_STORE)
                .body(ApiResult.success(bookingService.getMyBooking(bookingId)));
    }

    @GetMapping("/stats")
    @PreAuthorize("hasRole('DRIVER')")
    public ResponseEntity<ApiResult<BookingStatsResponse>> getMyBookingStats() {
        BookingStatsResponse response = bookingService.getMyBookingStats();
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_NO_STORE)
                .body(ApiResult.success(response));
    }
}
