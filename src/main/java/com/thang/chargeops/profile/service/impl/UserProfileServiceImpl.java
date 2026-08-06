package com.thang.chargeops.profile.service.impl;

import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.AuthErrorCode;
import com.thang.chargeops.profile.dto.UserProfileResponse;
import com.thang.chargeops.profile.dto.UserProfileUpdateRequest;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.mapper.UserProfileMapper;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserProfileServiceImpl implements UserProfileService {

    private static final String EMAIL_CLAIM = "email";
    private static final String NAME_CLAIM = "name";
    private static final String PREFERRED_USERNAME_CLAIM = "preferred_username";

    private final UserProfileRepository userProfileRepository;
    private final UserProfileMapper userProfileMapper;

    @Override
    @Transactional
    public UserProfileResponse getOrBootstrapProfile(Jwt jwt) {
        UserProfile profile = findOrCreateFromJwt(jwt);
        return userProfileMapper.toResponse(profile);
    }

    @Override
    @Transactional
    public UserProfileResponse updateCurrentProfile(Jwt jwt, UserProfileUpdateRequest request) {
        UserProfile profile = findOrCreateFromJwt(jwt);
        profile.setDisplayName(request.getFullName());
        profile.setPhone(request.getPhone());
        profile.setStatus(UserStatus.ACTIVE);

        return userProfileMapper.toResponse(userProfileRepository.save(profile));
    }

    private UserProfile findOrCreateFromJwt(Jwt jwt) {
        String keycloakId = jwt.getSubject();
        String email = jwt.getClaimAsString(EMAIL_CLAIM);
        if (!hasText(keycloakId) || !hasText(email)) {
            throw new AppException(AuthErrorCode.TOKEN_INVALID);
        }

        return userProfileRepository.findByKeycloakId(keycloakId)
                .map(profile -> syncProfileFromJwt(profile, email, getDisplayNameFromJwt(jwt)))
                .orElseGet(() -> userProfileRepository.save(UserProfile.builder()
                        .keycloakId(keycloakId)
                        .email(email)
                        .displayName(getDisplayNameFromJwt(jwt))
                        .status(UserStatus.ACTIVE)
                        .build()));
    }

    private UserProfile syncProfileFromJwt(UserProfile profile, String email, String displayName) {
        if (hasText(email) && !email.equals(profile.getEmail())) {
            profile.setEmail(email);
        }

        if (!hasText(profile.getDisplayName()) && hasText(displayName)) {
            profile.setDisplayName(displayName);
        }

        return profile;
    }

    private String getDisplayNameFromJwt(Jwt jwt) {
        String name = jwt.getClaimAsString(NAME_CLAIM);
        if (hasText(name)) {
            return name;
        }
        return jwt.getClaimAsString(PREFERRED_USERNAME_CLAIM);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
