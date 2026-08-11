package com.thang.chargeops.profile.service.impl;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.AuthErrorCode;
import com.thang.chargeops.exception.errorcode.ProfileErrorCode;
import com.thang.chargeops.profile.dto.UserProfileResponse;
import com.thang.chargeops.profile.dto.UserProfileUpdateRequest;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.mapper.UserProfileMapper;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserProfileServiceImpl implements UserProfileService {

    private static final String EMAIL_CLAIM = "email";
    private static final String NAME_CLAIM = "name";
    private static final String PREFERRED_USERNAME_CLAIM = "preferred_username";
    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^(.+)@(\\S+)$");

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

    private UserProfile findOrCreateFromJwt(Jwt jwt) {
        String keycloakId = getRequiredKeycloakId(jwt);
        String email = getEmailFromJwt(jwt);
        String displayName = getDisplayNameFromJwt(jwt);

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

    private String getRequiredKeycloakId(Jwt jwt) {
        String keycloakId = jwt.getSubject();
        if (!hasText(keycloakId)) {
            throw new AppException(AuthErrorCode.TOKEN_INVALID);
        }
        return keycloakId;
    }

    private String getEmailFromJwt(Jwt jwt) {
        String email = jwt.getClaimAsString(EMAIL_CLAIM);
        if (isEmail(email)) {
            return email;
        }

        String preferredUsername = jwt.getClaimAsString(PREFERRED_USERNAME_CLAIM);
        if (isEmail(preferredUsername)) {
            return preferredUsername;
        }
        throw new AppException(AuthErrorCode.EMAIL_CLAIM_MISSING);
    }

    private String getDisplayNameFromJwt(Jwt jwt) {
        String name = jwt.getClaimAsString(NAME_CLAIM);
        if (hasText(name)) {
            return name;
        }

        String preferredUsername = jwt.getClaimAsString(PREFERRED_USERNAME_CLAIM);
        return hasText(preferredUsername) && !isEmail(preferredUsername)
                ? preferredUsername
                : null;
    }

    private boolean isEmail(String value) {
        return hasText(value) && EMAIL_PATTERN.matcher(value).matches();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
