package com.thang.chargeops.profile.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.profile.dto.AdminUserProfileResponse;
import com.thang.chargeops.profile.dto.AdminUserStatusRequest;
import com.thang.chargeops.profile.dto.AdminUserStatusResponse;
import com.thang.chargeops.profile.dto.AdminUserSummaryResponse;
import com.thang.chargeops.profile.model.PrimaryRole;
import com.thang.chargeops.profile.service.AdminUserQueryService;
import com.thang.chargeops.profile.service.AdminUserStatusService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** UM-01: admin user management read API (UM-00 §6). */
@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "admin/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Validated
public class AdminUserController {

    private final AdminUserQueryService adminUsers;
    private final AdminUserStatusService adminUserStatus;

    @GetMapping
    public ResponseEntity<ApiResult<List<AdminUserProfileResponse>>> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) PrimaryRole role,
            @RequestParam(required = false) UserStatus status) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.successPage(adminUsers.list(page, size, q, role, status)));
    }

    @GetMapping("/summary")
    public ResponseEntity<ApiResult<AdminUserSummaryResponse>> summary(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) PrimaryRole role,
            @RequestParam(required = false) UserStatus status) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.success(adminUsers.summary(q, role, status)));
    }

    @GetMapping("/{profileId}")
    public ResponseEntity<ApiResult<AdminUserProfileResponse>> detail(@PathVariable UUID profileId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.success(adminUsers.detail(profileId)));
    }

    /** UM-02: suspend/resume DRIVERS + STAFF (UM-00 §6). */
    @PatchMapping("/{profileId}/status")
    public ResponseEntity<ApiResult<AdminUserStatusResponse>> updateStatus(
            @PathVariable UUID profileId,
            @Valid @RequestBody AdminUserStatusRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.success(adminUserStatus.updateStatus(profileId, request)));
    }
}
