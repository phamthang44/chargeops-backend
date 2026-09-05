package com.thang.chargeops.profile.service.impl;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.ProfileErrorCode;
import com.thang.chargeops.infra.security.JwtClaimExtractor;
import com.thang.chargeops.profile.dto.UserProfileResponse;
import com.thang.chargeops.profile.dto.UserProfileUpdateRequest;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.mapper.UserProfileMapper;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserProfileServiceImpl implements UserProfileService {

    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

    private final JwtClaimExtractor jwtClaimExtractor;
    private final UserProfileRepository userProfileRepository;
    private final UserProfileMapper userProfileMapper;

    @Override
    @Transactional
    public UserProfileResponse getOrBootstrapProfile(Jwt jwt) {
        UserProfile profile = findOrCreateFromJwt(jwt);
        flushProfileChanges();
        return userProfileMapper.toResponse(profile);
    }

    @Override
    @Transactional
    public UserProfileResponse updateCurrentProfile(Jwt jwt, UserProfileUpdateRequest request) {
        UserProfile profile = findOrCreateFromJwt(jwt);
        profile.setDisplayName(request.getDisplayName());
        profile.setPhone(request.getPhone());

        flushProfileChanges();
        return userProfileMapper.toResponse(profile);
    }

    @Override
    public UserProfile getUserProfileById(UUID id) {
        return userProfileRepository.findById(id).orElseThrow(
                () -> new AppException(ProfileErrorCode.PROFILE_NOT_FOUND)
        );
    }

    @Override
    public UserProfile getUserProfileByEmail(String email) {
        return userProfileRepository.findByEmail(email).orElse(null);
    }

    @Override
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public List<UserProfile> getStaffProfiles(List<UUID> ids) {
        return userProfileRepository.findAllById(ids);
    }

    private UserProfile findOrCreateFromJwt(Jwt jwt) {
        String keycloakId = jwtClaimExtractor.requireSubject(jwt);
        String email = jwtClaimExtractor.requireEmail(jwt);
        String displayName = jwtClaimExtractor.getDisplayName(jwt);

        return userProfileRepository.findByKeycloakId(keycloakId)
                .map(profile -> syncProfileFromJwt(profile, email, displayName))
                .orElseGet(() -> createOrLoadProfile(keycloakId, email, displayName));
    }

    private UserProfile createOrLoadProfile(String keycloakId, String email, String displayName) {
        int insertedRows;
        try {
            insertedRows = userProfileRepository.insertProfileIfAbsent(keycloakId, email, displayName);
        } catch (DataIntegrityViolationException exception) {
            throw translateProfileWriteException(exception);
        }

        UserProfile profile = userProfileRepository.findByKeycloakId(keycloakId)
                .orElseThrow(() -> new AppException(ProfileErrorCode.PROFILE_BOOTSTRAP_FAILED));

        log.info("Profile bootstrap completed: profileId={}, keycloakId={}, created={}",
                profile.getId(), keycloakId, insertedRows == 1);
        return syncProfileFromJwt(profile, email, displayName);
    }

    private UserProfile syncProfileFromJwt(UserProfile profile, String email, String displayName) {
        if (!email.equals(profile.getEmail())) {
            profile.setEmail(email);
        }

        if (!hasText(profile.getDisplayName()) && hasText(displayName)) {
            profile.setDisplayName(displayName);
        }
        return profile;
    }

    private void flushProfileChanges() {
        try {
            userProfileRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw translateProfileWriteException(exception);
        }
    }

    private RuntimeException translateProfileWriteException(DataIntegrityViolationException exception) {
        if (isUniqueConstraintViolation(exception)) {
            return new AppException(ProfileErrorCode.EMAIL_ALREADY_LINKED);
        }
        return exception;
    }

    private boolean isUniqueConstraintViolation(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException
                    && UNIQUE_VIOLATION_SQL_STATE.equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
