package com.thang.chargeops.profile.service;

import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.AuthErrorCode;
import com.thang.chargeops.exception.errorcode.ProfileErrorCode;
import com.thang.chargeops.infra.security.JwtClaimExtractor;
import com.thang.chargeops.profile.dto.UserProfileResponse;
import com.thang.chargeops.profile.dto.UserProfileUpdateRequest;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.mapper.UserProfileMapper;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.service.impl.UserProfileServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserProfileServiceImplTest {

    @Mock
    private UserProfileRepository userProfileRepository;

    @Mock
    private UserProfileMapper userProfileMapper;

    private UserProfileServiceImpl userProfileService;
    @Mock private com.thang.chargeops.moderation.service.ContentModerationService moderation;

    @BeforeEach
    void setUp() {
        userProfileService = new UserProfileServiceImpl(
                new JwtClaimExtractor(),
                userProfileRepository,
                userProfileMapper,
                moderation
        );
        org.mockito.Mockito.lenient().when(moderation.safeImportedDisplayName(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsProfileFromNewJwt() {
        Jwt jwt = jwt("keycloak-new", "driver@chargeops.vn", "Driver One");
        UserProfile created = profile("keycloak-new", "driver@chargeops.vn", UserStatus.ACTIVE);
        when(userProfileRepository.findByKeycloakId("keycloak-new"))
                .thenReturn(Optional.empty(), Optional.of(created));
        when(userProfileRepository.insertProfileIfAbsent(
                "keycloak-new", "driver@chargeops.vn", "Driver One"))
                .thenReturn(1);
        stubMapper();

        userProfileService.getOrBootstrapProfile(jwt);

        verify(userProfileRepository).insertProfileIfAbsent(
                "keycloak-new", "driver@chargeops.vn", "Driver One");
        verify(userProfileRepository).flush();
    }

    @Test
    void returnsExistingProfileWithoutTryingToInsertAgain() {
        UserProfile existing = profile("keycloak-existing", "driver@chargeops.vn", UserStatus.ACTIVE);
        when(userProfileRepository.findByKeycloakId("keycloak-existing"))
                .thenReturn(Optional.of(existing));
        stubMapper();

        userProfileService.getOrBootstrapProfile(
                jwt("keycloak-existing", "driver@chargeops.vn", "Driver One"));

        verify(userProfileRepository, never()).insertProfileIfAbsent(any(), any(), any());
        verify(userProfileRepository).flush();
    }

    @Test
    void usesPreferredUsernameWhenEmailClaimIsMissing() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("keycloak-fallback")
                .claim("preferred_username", "fallback@chargeops.vn")
                .build();
        UserProfile created = profile("keycloak-fallback", "fallback@chargeops.vn", UserStatus.ACTIVE);
        when(userProfileRepository.findByKeycloakId("keycloak-fallback"))
                .thenReturn(Optional.empty(), Optional.of(created));
        when(userProfileRepository.insertProfileIfAbsent(
                "keycloak-fallback", "fallback@chargeops.vn", null))
                .thenReturn(1);
        stubMapper();

        userProfileService.getOrBootstrapProfile(jwt);

        verify(userProfileRepository).insertProfileIfAbsent(
                "keycloak-fallback", "fallback@chargeops.vn", null);
    }

    @Test
    void rejectsTokenWithoutUsableEmail() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("keycloak-no-email")
                .claim("preferred_username", "not-an-email")
                .build();

        assertThatThrownBy(() -> userProfileService.getOrBootstrapProfile(jwt))
                .isInstanceOfSatisfying(AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AuthErrorCode.EMAIL_CLAIM_MISSING));
        verifyNoInteractions(userProfileRepository);
    }

    @Test
    void mapsDuplicateEmailToBusinessConflict() {
        when(userProfileRepository.findByKeycloakId("keycloak-new"))
                .thenReturn(Optional.empty());
        when(userProfileRepository.insertProfileIfAbsent(
                "keycloak-new", "shared@chargeops.vn", "New Driver"))
                .thenThrow(uniqueViolation());

        assertThatThrownBy(() -> userProfileService.getOrBootstrapProfile(
                jwt("keycloak-new", "shared@chargeops.vn", "New Driver")))
                .isInstanceOfSatisfying(AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ProfileErrorCode.EMAIL_ALREADY_LINKED));
    }

    @Test
    void updatesCurrentProfileWithoutChangingAccountStatus() {
        UserProfile existing = profile(
                "keycloak-current", "driver@chargeops.vn", UserStatus.SUSPENDED);
        when(userProfileRepository.findByKeycloakId("keycloak-current"))
                .thenReturn(Optional.of(existing));
        stubMapper();

        UserProfileUpdateRequest request = updateRequest("Nguyen Van A", "+84912345678");
        userProfileService.updateCurrentProfile(
                jwt("keycloak-current", "driver@chargeops.vn", "JWT Name"), request);

        assertThat(existing.getDisplayName()).isEqualTo("Nguyen Van A");
        assertThat(existing.getPhone()).isEqualTo("+84912345678");
        assertThat(existing.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        verify(userProfileRepository).flush();
    }

    @Test
    void mapsEmailConflictRaisedWhileFlushingProfileChanges() {
        UserProfile existing = profile(
                "keycloak-current", "old@chargeops.vn", UserStatus.ACTIVE);
        when(userProfileRepository.findByKeycloakId("keycloak-current"))
                .thenReturn(Optional.of(existing));
        doThrow(uniqueViolation()).when(userProfileRepository).flush();

        assertThatThrownBy(() -> userProfileService.getOrBootstrapProfile(
                jwt("keycloak-current", "new@chargeops.vn", "Driver")))
                .isInstanceOfSatisfying(AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ProfileErrorCode.EMAIL_ALREADY_LINKED));
    }

    @Test
    void reportsBootstrapFailureWhenProfileCannotBeLoadedAfterInsert() {
        when(userProfileRepository.findByKeycloakId("keycloak-new"))
                .thenReturn(Optional.empty(), Optional.empty());

        assertThatThrownBy(() -> userProfileService.getOrBootstrapProfile(
                jwt("keycloak-new", "driver@chargeops.vn", "Driver")))
                .isInstanceOfSatisfying(AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ProfileErrorCode.PROFILE_BOOTSTRAP_FAILED));
    }

    @Test
    void rejectsProhibitedNameBeforeProfileMutationOrFlush() {
        var existing = profile("moderation-user", "moderation@example.test", UserStatus.ACTIVE);
        existing.setDisplayName("Original");
        when(userProfileRepository.findByKeycloakId("moderation-user")).thenReturn(Optional.of(existing));
        org.mockito.Mockito.doThrow(new AppException(com.thang.chargeops.exception.errorcode.ModerationErrorCode.CONTENT_PROHIBITED))
                .when(moderation).validate("displayName", com.thang.chargeops.moderation.model.ContentScope.DISPLAY_NAME, "bad");
        assertThatThrownBy(() -> userProfileService.updateCurrentProfile(
                jwt("moderation-user", "moderation@example.test", "Original"), updateRequest("bad", "0912345678")))
                .isInstanceOf(AppException.class);
        assertThat(existing.getDisplayName()).isEqualTo("Original");
        verify(userProfileRepository, never()).flush();
    }

    @Test
    void ssoNameIsSanitizedBeforeNativeInsertAndBlankNameSynchronization() {
        var created = profile("moderation-sso", "sso@example.test", UserStatus.ACTIVE);
        when(userProfileRepository.findByKeycloakId("moderation-sso"))
                .thenReturn(Optional.empty(), Optional.of(created));
        when(moderation.safeImportedDisplayName("bad")).thenReturn("Người dùng");
        when(userProfileRepository.insertProfileIfAbsent("moderation-sso", "sso@example.test", "Người dùng"))
                .thenReturn(1);
        stubMapper();
        userProfileService.getOrBootstrapProfile(jwt("moderation-sso", "sso@example.test", "bad"));
        verify(userProfileRepository).insertProfileIfAbsent("moderation-sso", "sso@example.test", "Người dùng");
        assertThat(created.getDisplayName()).isEqualTo("Người dùng");
    }

    @Test
    void existingNonblankNameIsNotReplacedByBadSsoName() {
        var existing = profile("moderation-existing", "existing@example.test", UserStatus.ACTIVE);
        existing.setDisplayName("Valid existing name");
        when(userProfileRepository.findByKeycloakId("moderation-existing")).thenReturn(Optional.of(existing));
        stubMapper();
        userProfileService.getOrBootstrapProfile(jwt("moderation-existing", "existing@example.test", "bad"));
        assertThat(existing.getDisplayName()).isEqualTo("Valid existing name");
        verify(moderation, never()).safeImportedDisplayName(any());
    }

    private Jwt jwt(String subject, String email, String name) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(subject)
                .claim("email", email)
                .claim("name", name)
                .build();
    }

    private UserProfileUpdateRequest updateRequest(String displayName, String phone) {
        UserProfileUpdateRequest request = new UserProfileUpdateRequest();
        ReflectionTestUtils.setField(request, "displayName", displayName);
        ReflectionTestUtils.setField(request, "phone", phone);
        return request;
    }

    private UserProfile profile(String keycloakId, String email, UserStatus status) {
        UserProfile profile = UserProfile.builder()
                .keycloakId(keycloakId)
                .email(email)
                .status(status)
                .build();
        profile.setId(UUID.randomUUID());
        return profile;
    }

    private DataIntegrityViolationException uniqueViolation() {
        SQLException cause = new SQLException("duplicate key", "23505");
        return new DataIntegrityViolationException("unique constraint violation", cause);
    }

    private void stubMapper() {
        when(userProfileMapper.toResponse(any(UserProfile.class)))
                .thenReturn(new UserProfileResponse());
    }
}
