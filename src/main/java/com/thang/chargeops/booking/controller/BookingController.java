package com.thang.chargeops.booking.controller;


import com.thang.chargeops.booking.dto.request.CreateBookingRequest;
import com.thang.chargeops.booking.dto.request.PricePreviewRequest;
import com.thang.chargeops.booking.dto.response.CreateBookingResponse;
import com.thang.chargeops.booking.dto.response.PricePreviewResponse;
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

import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingPricingService bookingPricingService;
    private final BookingService bookingService;

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/price-preview")
    public ResponseEntity<ApiResult<PricePreviewResponse>> previewBookingPrice(
            @Valid @RequestBody PricePreviewRequest request
    ) {
        PricePreviewResponse response = bookingPricingService
                .previewBookingPrice(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
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
}
