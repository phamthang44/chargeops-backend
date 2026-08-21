package com.thang.chargeops.station.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.station.dto.license.request.IssueLicenseRequest;
import com.thang.chargeops.station.dto.license.response.AdminLicenseListItemResponse;
import com.thang.chargeops.station.service.LicenseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "admin/stations/{stationId}/licenses")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminStationLicenseController {

    private final LicenseService licenseService;

    @PostMapping
    public ResponseEntity<ApiResult<?>> issueLicense(
            @PathVariable UUID stationId,
            @Valid @RequestBody IssueLicenseRequest request
    ) {
        var response = licenseService.issueLicense(stationId, request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResult.success(response));
    }

    @GetMapping
    public ResponseEntity<ApiResult<List<AdminLicenseListItemResponse>>> getStationLicenseHistory(@PathVariable UUID stationId) {
        var histories = licenseService.getStationLicenseHistory(stationId);
        return ResponseEntity.ok(ApiResult.success(histories));
    }
}
