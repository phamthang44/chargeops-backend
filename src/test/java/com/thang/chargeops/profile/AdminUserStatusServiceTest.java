package com.thang.chargeops.profile;

import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.Role;
import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.ProfileErrorCode;
import com.thang.chargeops.exception.errorcode.UserErrorCode;
import com.thang.chargeops.infra.identity.IdentityRoleService;
import com.thang.chargeops.infra.websocket.WebSocketSessionRegistry;
import com.thang.chargeops.profile.dto.AdminUserStatusRequest;
import com.thang.chargeops.profile.dto.AdminUserStatusResponse;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.model.PrimaryRole;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.service.AdminUserStatusService;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserStatusServiceTest {

    @Mock EntityManager entityManager;
    @Mock UserProfileRepository userProfileRepository;
    @Mock CurrentProfileProvider currentProfile;
    @Mock IdentityRoleService identityRoleService;
    @Mock WebSocketSessionRegistry sessionRegistry;

    AdminUserStatusService service;

    UUID actorId = UUID.randomUUID();
    UserProfile actor;
    UserProfile driver;

    @BeforeEach
    void setUp() {
        service = new AdminUserStatusService(
                entityManager, userProfileRepository, currentProfile, identityRoleService, sessionRegistry);
        actor = profile(actorId, PrimaryRole.ADMIN, UserStatus.ACTIVE);
        driver = profile(UUID.randomUUID(), PrimaryRole.DRIVER, UserStatus.ACTIVE);
        lenient().when(currentProfile.requireProfile()).thenReturn(actor);
        lenient().when(identityRoleService.getRoles(driver.getKeycloakId()))
                .thenReturn(Set.of(Role.DRIVER));
    }

    @Test
    void suspendDriverWithoutActiveBookingSucceedsAndBumpsVersion() {
        when(userProfileRepository.findByIdWithLock(driver.getId())).thenReturn(Optional.of(driver));
        mockCount(0L);

        AdminUserStatusResponse response = service.updateStatus(driver.getId(),
                new AdminUserStatusRequest(UserStatus.SUSPENDED, 0L, "Vi phạm nội quy", UUID.randomUUID()));

        assertThat(response.status()).isEqualTo("SUSPENDED");
        assertThat(response.changed()).isTrue();
        assertThat(driver.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(driver.getStatusReason()).isEqualTo("Vi phạm nội quy");
        assertThat(driver.getStatusChangedBy()).isEqualTo(actorId);
        verify(sessionRegistry).closeSessionsByPrincipal(eq(driver.getKeycloakId()), anyString());
    }

    @Test
    void suspendDriverWithActiveBookingIsBlocked() {
        when(userProfileRepository.findByIdWithLock(driver.getId())).thenReturn(Optional.of(driver));
        mockCount(3L);

        assertThatThrownBy(() -> service.updateStatus(driver.getId(),
                new AdminUserStatusRequest(UserStatus.SUSPENDED, 0L, "reason", UUID.randomUUID())))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.SUSPEND_BLOCKED);

        assertThat(driver.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(sessionRegistry, never()).closeSessionsByPrincipal(any(), any());
    }

    @Test
    void suspendAdminIsRejected() {
        UserProfile adminTarget = profile(UUID.randomUUID(), PrimaryRole.ADMIN, UserStatus.ACTIVE);
        when(userProfileRepository.findByIdWithLock(adminTarget.getId())).thenReturn(Optional.of(adminTarget));

        assertThatThrownBy(() -> service.updateStatus(adminTarget.getId(),
                new AdminUserStatusRequest(UserStatus.SUSPENDED, 0L, "reason", UUID.randomUUID())))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.TARGET_PROTECTED);
    }

    @Test
    void suspendSelfIsRejected() {
        when(userProfileRepository.findByIdWithLock(actorId)).thenReturn(Optional.of(actor));

        assertThatThrownBy(() -> service.updateStatus(actorId,
                new AdminUserStatusRequest(UserStatus.SUSPENDED, 0L, "reason", UUID.randomUUID())))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.TARGET_PROTECTED);
    }

    @Test
    void suspendOwnerIsRejected() {
        UserProfile owner = profile(UUID.randomUUID(), PrimaryRole.OWNER, UserStatus.ACTIVE);
        when(userProfileRepository.findByIdWithLock(owner.getId())).thenReturn(Optional.of(owner));

        assertThatThrownBy(() -> service.updateStatus(owner.getId(),
                new AdminUserStatusRequest(UserStatus.SUSPENDED, 0L, "reason", UUID.randomUUID())))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.OWNER_MANAGED_OFFLINE);
    }

    @Test
    void staleVersionIsRejected() {
        when(userProfileRepository.findByIdWithLock(driver.getId())).thenReturn(Optional.of(driver));
        driver.setVersion(5L);

        assertThatThrownBy(() -> service.updateStatus(driver.getId(),
                new AdminUserStatusRequest(UserStatus.SUSPENDED, 0L, "reason", UUID.randomUUID())))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.VERSION_CONFLICT);
    }

    @Test
    void sameStateReturnsNoOpWithoutVersionCheck() {
        when(userProfileRepository.findByIdWithLock(driver.getId())).thenReturn(Optional.of(driver));
        driver.setVersion(5L);

        AdminUserStatusResponse response = service.updateStatus(driver.getId(),
                new AdminUserStatusRequest(UserStatus.ACTIVE, 0L, "reason", UUID.randomUUID()));

        assertThat(response.changed()).isFalse();
        assertThat(response.version()).isEqualTo(5L);
    }

    @Test
    void roleDriftIsRejected() {
        when(userProfileRepository.findByIdWithLock(driver.getId())).thenReturn(Optional.of(driver));
        when(identityRoleService.getRoles(driver.getKeycloakId())).thenReturn(Set.of(Role.OWNER));

        assertThatThrownBy(() -> service.updateStatus(driver.getId(),
                new AdminUserStatusRequest(UserStatus.SUSPENDED, 0L, "reason", UUID.randomUUID())))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.ROLE_UNVERIFIED);
    }

    @Test
    void unknownProjectionIsRejectedWithoutRemoteLookup() {
        driver.setPrimaryRole(PrimaryRole.UNKNOWN);
        when(userProfileRepository.findByIdWithLock(driver.getId())).thenReturn(Optional.of(driver));

        assertThatThrownBy(() -> service.updateStatus(driver.getId(),
                new AdminUserStatusRequest(UserStatus.SUSPENDED, 0L, "reason", UUID.randomUUID())))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", UserErrorCode.ROLE_UNVERIFIED);
    }

    @Test
    void inactiveAdminActorIsRejected() {
        actor.setStatus(UserStatus.SUSPENDED);

        assertThatThrownBy(() -> service.updateStatus(driver.getId(),
                new AdminUserStatusRequest(UserStatus.SUSPENDED, 0L, "reason", UUID.randomUUID())))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProfileErrorCode.PROFILE_NOT_ACTIVE);
    }

    @Test
    void missingTargetIsRejected() {
        UUID missing = UUID.randomUUID();
        when(userProfileRepository.findByIdWithLock(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatus(missing,
                new AdminUserStatusRequest(UserStatus.SUSPENDED, 0L, "reason", UUID.randomUUID())))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProfileErrorCode.PROFILE_NOT_FOUND);
    }

    @SuppressWarnings("unchecked")
    private void mockCount(long count) {
        TypedQuery<Long> query = mock(TypedQuery.class);
        lenient().when(query.setParameter(anyString(), any())).thenReturn(query);
        lenient().when(query.getSingleResult()).thenReturn(count);
        lenient().when(entityManager.createQuery(contains("Booking"), eq(Long.class))).thenReturn(query);
        lenient().when(entityManager.createQuery(contains("SupportTicket"), eq(Long.class))).thenReturn(query);
    }

    private UserProfile profile(UUID id, PrimaryRole role, UserStatus status) {
        UserProfile profile = UserProfile.builder()
                .keycloakId(UUID.randomUUID().toString())
                .email(id + "@test.local")
                .displayName("Test")
                .primaryRole(role)
                .status(status)
                .version(0L)
                .build();
        profile.setId(id);
        return profile;
    }
}
