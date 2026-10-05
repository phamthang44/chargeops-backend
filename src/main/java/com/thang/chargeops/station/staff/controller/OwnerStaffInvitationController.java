package com.thang.chargeops.station.staff.controller;

import com.thang.chargeops.station.staff.dto.StaffInvitationRequest;
import com.thang.chargeops.station.staff.dto.StaffInvitationResponse;
import com.thang.chargeops.station.staff.service.StaffInvitationService;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasRole('OWNER')")
@RequestMapping(SystemConstant.API_URL_PATTERN + "owner/stations/{stationId}/staff-invitations")
public class OwnerStaffInvitationController {
    private final StaffInvitationService service;

    @PostMapping
    public ResponseEntity<ApiResult<StaffInvitationResponse>> invite(
            @PathVariable UUID stationId, @Valid @RequestBody StaffInvitationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(ApiResult.success(service.invite(stationId, request.email())));
    }

    @GetMapping
    public ResponseEntity<ApiResult<Page<StaffInvitationResponse>>> list(
            @PathVariable UUID stationId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.success(service.list(stationId, page, size)));
    }

    @PostMapping("/{invitationId}/resend")
    public ResponseEntity<ApiResult<StaffInvitationResponse>> resend(
            @PathVariable UUID stationId, @PathVariable UUID invitationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.success(service.resend(stationId, invitationId)));
    }

    @DeleteMapping("/{invitationId}")
    public ResponseEntity<ApiResult<StaffInvitationResponse>> cancel(
            @PathVariable UUID stationId, @PathVariable UUID invitationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.success(service.cancel(stationId, invitationId)));
    }
}
