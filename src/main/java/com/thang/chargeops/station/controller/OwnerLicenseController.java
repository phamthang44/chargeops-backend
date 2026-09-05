package com.thang.chargeops.station.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.station.service.LicenseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "owner/licenses")
@RequiredArgsConstructor
@PreAuthorize("hasRole('OWNER')")
public class OwnerLicenseController {

    private final LicenseService licenseService;

    @GetMapping("/{stationId}")
    public ResponseEntity<ApiResult<?>> getMyLicenses(@PathVariable UUID stationId) {
        var response = licenseService.getMyLicenseByStationId(stationId);
        return ResponseEntity.status(HttpStatus.OK).body(ApiResult.success(response));
    }

}
