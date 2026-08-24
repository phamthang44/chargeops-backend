package com.thang.chargeops.station.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "owner/stations")
@RequiredArgsConstructor
@PreAuthorize("hasRole('OWNER')")
public class OwnerStationPricingController {

    @PostMapping("/{stationId}/pricing")
    public ResponseEntity<ApiResult<?>> createNewTouRateConfig() {
        return null;
    }

    @PutMapping("/{stationId}/pricing")
    public ResponseEntity<ApiResult<?>> updatePriceConfig() {
        return null;
    }
}
