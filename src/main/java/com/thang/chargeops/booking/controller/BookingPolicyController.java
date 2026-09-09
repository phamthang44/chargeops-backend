package com.thang.chargeops.booking.controller;

import com.thang.chargeops.booking.config.BookingPolicyConfig;
import com.thang.chargeops.booking.dto.response.BookingPolicyResponse;
import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

/**
 * Endpoint cung cấp chính sách đặt chỗ toàn hệ thống (BKG-002).
 * Contract: GET /api/v1/booking-policy
 */
@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "booking-policy")
@RequiredArgsConstructor
public class BookingPolicyController {

    private final BookingPolicyConfig bookingPolicyConfig;
    private final Clock applicationClock;

    @PreAuthorize("isAuthenticated()")
    @GetMapping
    public ResponseEntity<ApiResult<BookingPolicyResponse>> getBookingPolicy() {
        BookingPolicyResponse policy = bookingPolicyConfig.toPolicyResponse();
        ApiResult<BookingPolicyResponse> result = ApiResult.<BookingPolicyResponse>builder()
                .data(policy)
                .meta(ApiResult.Meta.builder()
                        .serverTime(applicationClock.millis())
                        .build())
                .build();
        return ResponseEntity.ok(result);
    }
}
