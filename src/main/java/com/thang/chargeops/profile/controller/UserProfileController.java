package com.thang.chargeops.profile.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.profile.dto.UserProfileResponse;
import com.thang.chargeops.profile.dto.UserProfileUpdateRequest;
import com.thang.chargeops.profile.service.UserProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping(SystemConstant.API_URL_PATTERN + "/me")
public class UserProfileController {

    private final UserProfileService userProfileService;

    @GetMapping
    public ResponseEntity<ApiResult<UserProfileResponse>> getOrBootstrapProfile(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(ApiResult.success(userProfileService.getOrBootstrapProfile(jwt)));
    }

    @PutMapping
    public ResponseEntity<ApiResult<UserProfileResponse>> updateCurrentProfile(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UserProfileUpdateRequest request
    ) {
        return ResponseEntity.ok(ApiResult.success(userProfileService.updateCurrentProfile(jwt, request)));
    }

}
