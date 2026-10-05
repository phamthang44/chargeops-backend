package com.thang.chargeops.station.staff.service;

import com.thang.chargeops.station.staff.service.impl.StaffInvitationServiceImpl;
import com.thang.chargeops.exception.errorcode.StaffInvitationErrorCode;
import com.thang.chargeops.station.staff.dto.StaffInvitationResponse;
import com.thang.chargeops.station.staff.entity.StaffInvitation;
import com.thang.chargeops.station.staff.entity.StaffInvitationStatus;
import com.thang.chargeops.station.staff.repository.StaffInvitationRepository;

import com.thang.chargeops.common.enums.Role;
import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.infra.identity.IdentityRoleService;
import com.thang.chargeops.infra.security.JwtClaimExtractor;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.service.UserProfileService;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.staff.entity.StaffAssignmentStatus;
import com.thang.chargeops.station.staff.entity.StationStaffAssignment;
import com.thang.chargeops.station.staff.repository.StationStaffAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StaffInvitationServiceTest {
    @Mock StaffInvitationRepository invitations;
    @Mock StationRepository stations;
    @Mock UserProfileRepository profiles;
    @Mock StationStaffAssignmentRepository assignments;
    @Mock CurrentProfileProvider currentProfile;
    @Mock StaffIdentityProvisioner identity;
    @Mock IdentityRoleService identityRoles;
    @Mock UserProfileService profileService;
    @Mock PlatformTransactionManager transactions;

    private final Instant now = Instant.parse("2026-10-05T04:00:00Z");
    private StaffInvitationService service;

    @BeforeEach
    void setUp() {
        service = new StaffInvitationServiceImpl(invitations, stations, profiles, assignments,
                currentProfile, identity, identityRoles, profileService, new JwtClaimExtractor(),
                transactions, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void inviteProvisionsStaffOnlyIdentityButLeavesAssignmentInactive() {
        UUID stationId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        UserProfile owner = profile("owner", UserStatus.ACTIVE);
        Station station = station(stationId, owner);
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        when(stations.findById(stationId)).thenReturn(Optional.of(station));
        when(currentProfile.requireProfile()).thenReturn(owner);
        when(currentProfile.requireProfileId()).thenReturn(owner.getId());
        when(profiles.findByEmail("staff@example.com")).thenReturn(Optional.empty());
        when(invitations.saveAndFlush(any())).thenAnswer(call -> {
            StaffInvitation invitation = call.getArgument(0);
            if (invitation.getId() == null) invitation.setId(invitationId);
            saved = invitation;
            return invitation;
        });
        when(invitations.findById(invitationId)).thenAnswer(call -> Optional.of(saved));
        when(invitations.findByIdForUpdate(invitationId)).thenAnswer(call -> Optional.of(saved));
        when(identity.ensureStaffIdentity(invitationId, "staff@example.com")).thenReturn("kc-staff");

        StaffInvitationResponse response = service.invite(stationId, " STAFF@Example.com ");

        assertThat(response.status()).isEqualTo(StaffInvitationStatus.SENT);
        assertThat(response.expiresAt()).isEqualTo(now.plusSeconds(7 * 24 * 3600));
        verify(identity).sendActivationEmail("kc-staff");
        verifyNoInteractions(assignments);
    }

    private StaffInvitation saved;

    @Test
    void activationRequiresVerifiedStaffOnlyIdentityAndCreatesOneActiveAssignment() {
        UUID stationId = UUID.randomUUID();
        UserProfile owner = profile("owner", UserStatus.ACTIVE);
        UserProfile staff = profile("kc-staff", UserStatus.ACTIVE);
        staff.setEmail("staff@example.com");
        Station station = station(stationId, owner);
        StaffInvitation invitation = StaffInvitation.pending(station, "staff@example.com", owner.getId(),
                now.minusSeconds(60), now.plusSeconds(300));
        invitation.setId(UUID.randomUUID());
        invitation.sent("kc-staff", now.minusSeconds(30), now.plusSeconds(300));
        Jwt jwt = jwt(true);
        when(identityRoles.getRoles("kc-staff")).thenReturn(Set.of(Role.STAFF));
        when(invitations.findByIdentityUserIdAndStatusForUpdate("kc-staff", StaffInvitationStatus.SENT))
                .thenReturn(Optional.of(invitation));
        when(profiles.findByKeycloakId("kc-staff")).thenReturn(Optional.of(staff));
        when(assignments.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(invitations.saveAndFlush(invitation)).thenReturn(invitation);

        StaffInvitationResponse response = service.activate(jwt);

        assertThat(response.status()).isEqualTo(StaffInvitationStatus.ACCEPTED);
        verify(profileService).getOrBootstrapProfile(jwt);
        verify(assignments).saveAndFlush(argThat(assignment -> assignment.getStaff().getId().equals(staff.getId())
                && assignment.getStation().getId().equals(stationId)
                && assignment.getStatus() == StaffAssignmentStatus.ACTIVE));
    }

    @Test
    void activationRejectsUnverifiedEmailBeforeCreatingProfileOrAssignment() {
        assertThatThrownBy(() -> service.activate(jwt(false)))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(StaffInvitationErrorCode.IDENTITY_CONFLICT);
        verifyNoInteractions(profileService, assignments, identityRoles);
    }

    private Jwt jwt(boolean verified) {
        return Jwt.withTokenValue("token").header("alg", "none").subject("kc-staff")
                .claim("email", "staff@example.com").claim("email_verified", verified).build();
    }

    private static UserProfile profile(String keycloakId, UserStatus status) {
        UserProfile profile = UserProfile.builder().keycloakId(keycloakId)
                .email(keycloakId + "@example.com").status(status).build();
        profile.setId(UUID.randomUUID());
        return profile;
    }

    private static Station station(UUID id, UserProfile owner) {
        Station station = mock(Station.class);
        when(station.getId()).thenReturn(id);
        when(station.getOwner()).thenReturn(owner);
        when(station.getStatus()).thenReturn(StationStatus.ACTIVE);
        return station;
    }
}
