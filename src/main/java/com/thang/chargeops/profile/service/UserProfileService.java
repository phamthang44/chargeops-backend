package com.thang.chargeops.profile.service;

import com.thang.chargeops.profile.dto.UserProfileResponse;
import com.thang.chargeops.profile.dto.UserProfileUpdateRequest;
import org.springframework.security.oauth2.jwt.Jwt;

public interface UserProfileService {

    UserProfileResponse getOrBootstrapProfile(Jwt jwt);

    UserProfileResponse updateCurrentProfile(Jwt jwt, UserProfileUpdateRequest request);
}
