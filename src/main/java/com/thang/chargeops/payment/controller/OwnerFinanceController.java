package com.thang.chargeops.payment.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.payment.dto.response.OwnerFinanceBookingResponse;
import com.thang.chargeops.payment.dto.response.OwnerFinanceSummaryResponse;
import com.thang.chargeops.payment.service.OwnerFinanceService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@Profile({"dev", "demo", "test"})
@RequestMapping(SystemConstant.API_URL_PATTERN + "owner/finance")
@PreAuthorize("hasRole('OWNER')")
@RequiredArgsConstructor
@Validated
public class OwnerFinanceController {
    private final OwnerFinanceService service;

    @GetMapping("/summary")
    public ResponseEntity<ApiResult<OwnerFinanceSummaryResponse>> summary() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.success(service.summary()));
    }

    @GetMapping("/bookings")
    public ResponseEntity<ApiResult<List<OwnerFinanceBookingResponse>>> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.successPage(service.list(page, size)));
    }

    @GetMapping("/bookings/{bookingId}")
    public ResponseEntity<ApiResult<OwnerFinanceBookingResponse>> get(@PathVariable UUID bookingId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.success(service.get(bookingId)));
    }
}
