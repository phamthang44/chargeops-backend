package com.thang.chargeops.station.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.station.dto.license.filter.LicenseFilter;
import com.thang.chargeops.station.dto.license.request.LicenseStatusChangeRequest;
import com.thang.chargeops.station.dto.license.request.RenewLicenseRequest;
import com.thang.chargeops.station.dto.license.response.AdminLicenseListItemResponse;
import com.thang.chargeops.station.service.LicenseService;
import com.thang.chargeops.station.service.LicenseStatusEventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "admin/licenses")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminLicenseController {

    private final LicenseService licenseService;
    private final LicenseStatusEventService licenseStatusEventService;

    @GetMapping
    public ResponseEntity<ApiResult<?>> getLicenses(
            @ModelAttribute LicenseFilter filter,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "8") int pageSize) {

        Page<AdminLicenseListItemResponse> page = licenseService.searchLicenses(pageNo, pageSize, filter);

        return ResponseEntity.ok(ApiResult.successPage(page));
    }

    @GetMapping("/{licenseId}")
    public ResponseEntity<ApiResult<?>> getLicenseDetail(
            @PathVariable UUID licenseId) {

        var detail = licenseService.getLicenseDetail(licenseId);

        return ResponseEntity.ok(ApiResult.success(detail));
    }

    @GetMapping("/{licenseId}/status-events")
    public ResponseEntity<ApiResult<?>> getLicenseStatusEvents(
            @PathVariable UUID licenseId) {

        var events = licenseStatusEventService.getLicenseStatusEvents(licenseId);

        return ResponseEntity.ok(ApiResult.success(events));
    }

    @PostMapping("/{licenseId}/suspend")
    public ResponseEntity<Void> suspendLicense(
            @PathVariable UUID licenseId,
            @Valid @RequestBody LicenseStatusChangeRequest request) {

        licenseService.suspendLicense(licenseId, request.reason());

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{licenseId}/cancel")
    public ResponseEntity<Void> cancelLicense(
            @PathVariable UUID licenseId,
            @Valid @RequestBody LicenseStatusChangeRequest request) {

        licenseService.cancelLicense(licenseId, request.reason());

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{licenseId}/reactivate")
    public ResponseEntity<Void> reactivateLicense(
            @PathVariable UUID licenseId,
            @Valid @RequestBody LicenseStatusChangeRequest request) {

        licenseService.reactivateLicense(licenseId, request.reason());

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{licenseId}/renew")
    public ResponseEntity<ApiResult<?>> renewLicense(
            @PathVariable UUID licenseId,
            @Valid @RequestBody RenewLicenseRequest request) {

        var renewedLicense = licenseService.renewLicense(licenseId, request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResult.success(renewedLicense));
    }
}
