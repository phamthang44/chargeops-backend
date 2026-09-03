package com.thang.chargeops.station.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.station.dto.station.request.UpdateStationPricingRequest;
import com.thang.chargeops.station.dto.station.response.StationPricingResponse;
import com.thang.chargeops.station.dto.station.response.StationScheduleHistoryResponse;
import com.thang.chargeops.station.service.StationConfigurationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "owner/stations")
@RequiredArgsConstructor
@PreAuthorize("hasRole('OWNER')")
/*
 * TODO(staff-access): Giữ toàn bộ pricing OWNER-only. Không thay kiểm tra ownership bằng
 * StationAccessService.requireOwnerOrActiveStaff() khi triển khai các quyền Staff ở phase sau.
 */
public class OwnerStationPricingController {

    private final StationConfigurationService stationConfigurationService;

    @GetMapping("/{stationId}/pricing")
    public ResponseEntity<ApiResult<StationPricingResponse>> getPricing(
            @PathVariable UUID stationId
    ) {
        return ResponseEntity.ok(ApiResult.success(
                stationConfigurationService.getConfiguration(stationId)
        ));
    }

    @PutMapping("/{stationId}/pricing")
    public ResponseEntity<ApiResult<StationPricingResponse>> updatePricing(
            @PathVariable UUID stationId,
            @Valid @RequestBody UpdateStationPricingRequest request
    ) {
        return ResponseEntity.ok(ApiResult.success(
                stationConfigurationService.updateConfiguration(stationId, request)
        ));
    }

    @GetMapping("/{stationId}/pricing/schedule-history")
    public ResponseEntity<ApiResult<List<StationScheduleHistoryResponse>>> getScheduleHistory(
            @PathVariable UUID stationId
    ) {
        return ResponseEntity.ok(ApiResult.success(
                stationConfigurationService.getScheduleHistory(stationId)
        ));
    }
}
