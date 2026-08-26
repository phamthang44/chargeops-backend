package com.thang.chargeops.station.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.station.dto.station.request.UpdateStationPricingRequest;
import com.thang.chargeops.station.dto.station.response.StationPricingResponse;
import com.thang.chargeops.station.service.StationPricingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "owner/stations")
@RequiredArgsConstructor
@PreAuthorize("hasRole('OWNER')")
public class OwnerStationPricingController {

    private final StationPricingService stationPricingService;

    @GetMapping("/{stationId}/pricing")
    public ResponseEntity<ApiResult<StationPricingResponse>> getPricing(
            @PathVariable UUID stationId
    ) {
        return ResponseEntity.ok(ApiResult.success(
                stationPricingService.getStationPricing(stationId)
        ));
    }

    @PutMapping("/{stationId}/pricing")
    public ResponseEntity<ApiResult<StationPricingResponse>> updatePricing(
            @PathVariable UUID stationId,
            @Valid @RequestBody UpdateStationPricingRequest request
    ) {
        return ResponseEntity.ok(ApiResult.success(
                stationPricingService.updateStationPricing(stationId, request)
        ));
    }
}
