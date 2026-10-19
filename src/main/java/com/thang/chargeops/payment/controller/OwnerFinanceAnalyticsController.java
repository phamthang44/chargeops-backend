package com.thang.chargeops.payment.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.payment.dto.response.OwnerFinanceAnalyticsResponse;
import com.thang.chargeops.payment.service.OwnerFinanceAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@Profile({"dev", "demo", "test"})
@RequestMapping(SystemConstant.API_URL_PATTERN + "owner/finance")
@PreAuthorize("hasRole('OWNER')")
@RequiredArgsConstructor
public class OwnerFinanceAnalyticsController {
    private final OwnerFinanceAnalyticsService service;

    @GetMapping("/analytics")
    public ResponseEntity<ApiResult<OwnerFinanceAnalyticsResponse>> analytics(
            @RequestParam(required = false) UUID stationId,
            @RequestParam(defaultValue = "7d") String period) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.success(service.analytics(stationId, period)));
    }
}
