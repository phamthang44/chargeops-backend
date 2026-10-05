package com.thang.chargeops.station.staff.controller;

import com.thang.chargeops.station.staff.dto.StaffInvitationResponse;
import com.thang.chargeops.station.staff.service.StaffInvitationService;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('STAFF')")
@RequestMapping(SystemConstant.API_URL_PATTERN + "me/staff-invitation")
public class CurrentStaffInvitationController {
    private final StaffInvitationService service;

    @PostMapping("/activate")
    public ResponseEntity<ApiResult<StaffInvitationResponse>> activate(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.success(service.activate(jwt)));
    }
}
