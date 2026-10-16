package com.thang.chargeops.station.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.station.dto.dashboard.OwnerDashboardActivityResponse;
import com.thang.chargeops.station.dto.dashboard.OwnerDashboardAttentionPageResponse;
import com.thang.chargeops.station.dto.dashboard.OwnerDashboardStationPageResponse;
import com.thang.chargeops.station.service.OwnerDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "owner/dashboard")
@PreAuthorize("hasRole('OWNER')")
@RequiredArgsConstructor
public class OwnerDashboardController {
    private final OwnerDashboardService dashboardService;

    @GetMapping("/activity")
    public ResponseEntity<ApiResult<OwnerDashboardActivityResponse>> activity(
            @RequestParam(required = false) UUID stationId,
            @RequestParam(defaultValue = "7d") String period
    ) {
        return noStore(dashboardService.activity(stationId, period));
    }

    @GetMapping("/stations")
    public ResponseEntity<ApiResult<OwnerDashboardStationPageResponse>> stations(
            @RequestParam(required = false) UUID stationId,
            @RequestParam(defaultValue = "7d") String period,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "5") int size
    ) {
        return noStore(dashboardService.stations(stationId, period, page, size));
    }

    @GetMapping("/attention")
    public ResponseEntity<ApiResult<OwnerDashboardAttentionPageResponse>> attention(
            @RequestParam(required = false) UUID stationId,
            @RequestParam(defaultValue = "EQUIPMENT") String kind,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "5") int size
    ) {
        return noStore(dashboardService.attention(stationId, kind, page, size));
    }

    private static <T> ResponseEntity<ApiResult<T>> noStore(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResult.success(data));
    }
}
