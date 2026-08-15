package com.thang.chargeops.profile.service;

import com.thang.chargeops.profile.dto.UserProfileResponse;
import com.thang.chargeops.profile.dto.UserProfileUpdateRequest;
import com.thang.chargeops.profile.entity.UserProfile;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

public interface UserProfileService {

    UserProfileResponse getOrBootstrapProfile(Jwt jwt);

    UserProfileResponse updateCurrentProfile(Jwt jwt, UserProfileUpdateRequest request);

    UserProfile getUserProfileById(UUID id);
}
