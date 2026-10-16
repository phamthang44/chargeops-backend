package com.thang.chargeops.station.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.station.dto.station.response.OwnerOperationsSummaryResponse;
import com.thang.chargeops.station.service.StationService;
import com.thang.chargeops.station.service.OwnerDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "owner")
@PreAuthorize("hasRole('OWNER')")
@RequiredArgsConstructor
public class OwnerOperationsSummaryController {
    private final StationService service;
    private final OwnerDashboardService dashboardService;

    @GetMapping("/stations/summary")
    public ResponseEntity<ApiResult<OwnerOperationsSummaryResponse.Stations>> stations() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.success(service.getOwnerOperationsSummary().stations()));
    }

    @GetMapping("/charge-points/summary")
    public ResponseEntity<ApiResult<OwnerOperationsSummaryResponse.Hardware>> hardware() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.success(service.getOwnerOperationsSummary().hardware()));
    }

    @GetMapping("/dashboard/summary")
    public ResponseEntity<ApiResult<OwnerOperationsSummaryResponse>> dashboard() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.success(dashboardService.summary()));
    }
}
