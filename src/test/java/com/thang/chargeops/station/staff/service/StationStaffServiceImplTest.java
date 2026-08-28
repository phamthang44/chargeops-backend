package com.thang.chargeops.station.staff.service;

import com.thang.chargeops.common.enums.Role;
import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationStaffErrorCode;
import com.thang.chargeops.infra.identity.IdentityRoleService;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.service.UserProfileService;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.staff.dto.CurrentStaffContextResponse;
import com.thang.chargeops.station.staff.dto.StaffLookupResponse;
import com.thang.chargeops.station.staff.dto.StaffLookupStatus;
import com.thang.chargeops.station.staff.dto.StationStaffResponse;
import com.thang.chargeops.station.staff.entity.StaffAssignmentStatus;
import com.thang.chargeops.station.staff.entity.StationStaffAssignment;
import com.thang.chargeops.station.staff.policy.StationStaffPolicy;
import com.thang.chargeops.station.staff.repository.StationStaffAssignmentRepository;
import com.thang.chargeops.station.staff.service.impl.StationStaffServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.hibernate.exception.ConstraintViolationException;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StationStaffServiceImplTest {

    @Mock
    private StationStaffAssignmentRepository assignmentRepository;
    @Mock
    private UserProfileService userProfileService;
    @Mock
    private StationRepository stationRepository;
    @Mock
    private CurrentProfileProvider currentProfileProvider;
    @Mock
    private StationStaffPolicy policy;
    @Mock
    private IdentityRoleService identityRoleService;

    private StationStaffServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StationStaffServiceImpl(
                assignmentRepository,
                userProfileService,
                stationRepository,
                currentProfileProvider,
                policy,
                identityRoleService
        );
    }

    @Test
    void returnsEligibleCandidateAndNormalizesEmail() {
        UUID stationId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Station station = new Station();
        UserProfile candidate = profile("keycloak-driver", "driver@chargeops.vn", UserStatus.ACTIVE);
        candidate.setDisplayName("Driver One");
        candidate.setPhone("0987654321");

        when(stationRepository.findById(stationId)).thenReturn(Optional.of(station));
        when(currentProfileProvider.requireProfileId()).thenReturn(ownerId);
        when(userProfileService.getUserProfileByEmail("driver@chargeops.vn"))
                .thenReturn(candidate);
        when(assignmentRepository.existsByStaff_IdAndStatus(
                candidate.getId(), StaffAssignmentStatus.ACTIVE))
                .thenReturn(false);
        when(identityRoleService.getRoles("keycloak-driver")).thenReturn(Set.of(Role.DRIVER));

        StaffLookupResponse response = service.lookUpEmail("  DRIVER@ChargeOps.vn ", stationId);

        assertThat(response.exists()).isTrue();
        assertThat(response.userId()).isEqualTo(candidate.getId());
        assertThat(response.email()).isEqualTo("driver@chargeops.vn");
        assertThat(response.maskedPhone()).isEqualTo("098****321");
        assertThat(response.assignable()).isTrue();
        assertThat(response.status()).isEqualTo(StaffLookupStatus.ELIGIBLE);
        verify(policy).requireOwnerCanManageStaff(station, ownerId);
    }

    @Test
    void returnsNotFoundWithoutCallingKeycloak() {
        UUID stationId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Station station = new Station();

        when(stationRepository.findById(stationId)).thenReturn(Optional.of(station));
        when(currentProfileProvider.requireProfileId()).thenReturn(ownerId);
        when(userProfileService.getUserProfileByEmail("missing@chargeops.vn")).thenReturn(null);

        StaffLookupResponse response = service.lookUpEmail("missing@chargeops.vn", stationId);

        assertThat(response.exists()).isFalse();
        assertThat(response.assignable()).isFalse();
        assertThat(response.status()).isEqualTo(StaffLookupStatus.NOT_FOUND);
        verifyNoInteractions(identityRoleService);
        verifyNoInteractions(assignmentRepository);
    }

    @Test
    void reportsExistingActiveAssignmentBeforeReadingRoles() {
        UUID stationId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Station station = new Station();
        UserProfile candidate = profile("keycloak-staff", "staff@chargeops.vn", UserStatus.ACTIVE);

        when(stationRepository.findById(stationId)).thenReturn(Optional.of(station));
        when(currentProfileProvider.requireProfileId()).thenReturn(ownerId);
        when(userProfileService.getUserProfileByEmail("staff@chargeops.vn")).thenReturn(candidate);
        when(assignmentRepository.existsByStaff_IdAndStatus(
                candidate.getId(), StaffAssignmentStatus.ACTIVE))
                .thenReturn(true);

        StaffLookupResponse response = service.lookUpEmail("staff@chargeops.vn", stationId);

        assertThat(response.assignable()).isFalse();
        assertThat(response.status()).isEqualTo(StaffLookupStatus.ALREADY_ASSIGNED);
        verify(identityRoleService, never()).getRoles(candidate.getKeycloakId());
    }

    @Test
    void assignsEligibleStaffUsingDatabaseAssignmentOnly() {
        UUID stationId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Station station = station(stationId);
        UserProfile candidate = profile(
                "keycloak-driver",
                "driver@chargeops.vn",
                UserStatus.ACTIVE
        );

        when(currentProfileProvider.requireProfileId()).thenReturn(ownerId);
        when(stationRepository.findById(stationId)).thenReturn(Optional.of(station));
        when(userProfileService.getUserProfileByEmail("driver@chargeops.vn"))
                .thenReturn(candidate);
        when(identityRoleService.getRoles(candidate.getKeycloakId()))
                .thenReturn(Set.of(Role.DRIVER));
        when(assignmentRepository.existsByStaff_IdAndStatus(
                candidate.getId(),
                StaffAssignmentStatus.ACTIVE
        )).thenReturn(false);
        when(assignmentRepository.saveAndFlush(any(StationStaffAssignment.class)))
                .thenAnswer(invocation -> {
                    StationStaffAssignment assignment = invocation.getArgument(0);
                    assignment.setId(UUID.randomUUID());
                    return assignment;
                });

        StationStaffResponse response = service.assignStaff(
                stationId,
                " DRIVER@ChargeOps.vn ",
                "  Ca sáng  "
        );

        ArgumentCaptor<StationStaffAssignment> assignmentCaptor =
                ArgumentCaptor.forClass(StationStaffAssignment.class);
        verify(assignmentRepository).saveAndFlush(assignmentCaptor.capture());
        StationStaffAssignment saved = assignmentCaptor.getValue();
        assertThat(saved.getNote()).isEqualTo("Ca sáng");
        assertThat(saved.getAssignedBy()).isEqualTo(ownerId);
        assertThat(saved.getAssignedAt()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(StaffAssignmentStatus.ACTIVE);
        assertThat(response.assignmentId()).isEqualTo(saved.getId());
        assertThat(response.userId()).isEqualTo(candidate.getId());
        verify(policy).requireCanAssignStaff(
                station,
                ownerId,
                candidate,
                Set.of(Role.DRIVER),
                false
        );
    }

    @Test
    void mapsConcurrentActiveAssignmentUniqueViolationToBusinessConflict() {
        UUID stationId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Station station = station(stationId);
        UserProfile candidate = profile(
                "keycloak-driver",
                "driver@chargeops.vn",
                UserStatus.ACTIVE
        );
        ConstraintViolationException constraintViolation =
                org.mockito.Mockito.mock(ConstraintViolationException.class);

        when(currentProfileProvider.requireProfileId()).thenReturn(ownerId);
        when(stationRepository.findById(stationId)).thenReturn(Optional.of(station));
        when(userProfileService.getUserProfileByEmail(candidate.getEmail()))
                .thenReturn(candidate);
        when(identityRoleService.getRoles(candidate.getKeycloakId()))
                .thenReturn(Set.of(Role.DRIVER));
        when(assignmentRepository.existsByStaff_IdAndStatus(
                candidate.getId(),
                StaffAssignmentStatus.ACTIVE
        )).thenReturn(false);
        when(constraintViolation.getConstraintName())
                .thenReturn("uq_station_staff_assignments_one_active_per_user");
        when(assignmentRepository.saveAndFlush(any(StationStaffAssignment.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate active assignment",
                        constraintViolation
                ));

        assertThatThrownBy(() -> service.assignStaff(
                stationId,
                candidate.getEmail(),
                null
        )).isInstanceOfSatisfying(
                AppException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(StationStaffErrorCode.ACTIVE_ASSIGNMENT_ALREADY_EXISTS)
        );
    }

    @Test
    void rejectsAssignWhenCandidateDoesNotExist() {
        UUID stationId = UUID.randomUUID();
        when(currentProfileProvider.requireProfileId()).thenReturn(UUID.randomUUID());
        when(stationRepository.findById(stationId))
                .thenReturn(Optional.of(station(stationId)));
        when(userProfileService.getUserProfileByEmail("missing@chargeops.vn"))
                .thenReturn(null);

        assertThatThrownBy(() -> service.assignStaff(
                stationId,
                " MISSING@chargeops.vn ",
                null
        )).isInstanceOfSatisfying(
                AppException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(StationStaffErrorCode.CANDIDATE_NOT_FOUND)
        );
        verifyNoInteractions(identityRoleService);
        verify(assignmentRepository, never()).saveAndFlush(any());
    }

    @Test
    void revokesActiveAssignmentUsingDatabaseAssignmentOnly() {
        UUID stationId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Station station = station(stationId);
        UserProfile staff = profile(
                "keycloak-staff",
                "staff@chargeops.vn",
                UserStatus.ACTIVE
        );
        StationStaffAssignment assignment = StationStaffAssignment.builder()
                .station(station)
                .staff(staff)
                .status(StaffAssignmentStatus.ACTIVE)
                .assignedBy(ownerId)
                .assignedAt(Instant.now())
                .build();
        assignment.setId(assignmentId);

        when(currentProfileProvider.requireProfileId()).thenReturn(ownerId);
        when(assignmentRepository.findByIdAndStation_Id(assignmentId, stationId))
                .thenReturn(Optional.of(assignment));
        when(assignmentRepository.saveAndFlush(assignment)).thenReturn(assignment);

        StationStaffResponse response = service.revokeStaff(stationId, assignmentId);

        assertThat(assignment.getStatus()).isEqualTo(StaffAssignmentStatus.REVOKED);
        assertThat(assignment.getRevokedBy()).isEqualTo(ownerId);
        assertThat(assignment.getRevokedAt()).isNotNull();
        assertThat(response.status()).isEqualTo(StaffAssignmentStatus.REVOKED);
        verify(policy).requireCanRevokeStaff(assignment, stationId, ownerId);
        verifyNoInteractions(identityRoleService);
    }

    @Test
    void rejectsRevokeWhenAssignmentIsOutsideRequestedStation() {
        UUID stationId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        when(currentProfileProvider.requireProfileId()).thenReturn(UUID.randomUUID());
        when(assignmentRepository.findByIdAndStation_Id(assignmentId, stationId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revokeStaff(stationId, assignmentId))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(StationStaffErrorCode.ASSIGNMENT_NOT_FOUND)
                );
        verifyNoInteractions(identityRoleService);
        verify(policy, never()).requireCanRevokeStaff(any(), any(), any());
    }

    @Test
    void returnsCurrentActiveStaffContextFromDatabase() {
        UUID profileId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();
        UUID stationId = UUID.randomUUID();
        Station station = station(stationId);
        station.setStationCode("ST-1001");
        StationStaffAssignment assignment = StationStaffAssignment.builder()
                .station(station)
                .staff(profile("keycloak-staff", "staff@chargeops.vn", UserStatus.ACTIVE))
                .status(StaffAssignmentStatus.ACTIVE)
                .assignedBy(UUID.randomUUID())
                .assignedAt(Instant.now())
                .build();
        assignment.setId(assignmentId);

        when(currentProfileProvider.requireProfileId()).thenReturn(profileId);
        when(assignmentRepository.findByStaff_IdAndStatus(
                profileId,
                StaffAssignmentStatus.ACTIVE
        )).thenReturn(Optional.of(assignment));

        CurrentStaffContextResponse response = service.getCurrentStaffContext();

        assertThat(response.staff()).isTrue();
        assertThat(response.assignmentId()).isEqualTo(assignmentId);
        assertThat(response.assignmentStatus()).isEqualTo(StaffAssignmentStatus.ACTIVE);
        assertThat(response.station().id()).isEqualTo(stationId);
        assertThat(response.station().stationCode()).isEqualTo("ST-1001");
        assertThat(response.station().name()).isEqualTo("ChargeOps Station");
        verifyNoInteractions(identityRoleService);
    }

    @Test
    void returnsNonStaffContextWhenCurrentUserHasNoActiveAssignment() {
        UUID profileId = UUID.randomUUID();
        when(currentProfileProvider.requireProfileId()).thenReturn(profileId);
        when(assignmentRepository.findByStaff_IdAndStatus(
                profileId,
                StaffAssignmentStatus.ACTIVE
        )).thenReturn(Optional.empty());

        CurrentStaffContextResponse response = service.getCurrentStaffContext();

        assertThat(response.staff()).isFalse();
        assertThat(response.assignmentId()).isNull();
        assertThat(response.assignmentStatus()).isNull();
        assertThat(response.station()).isNull();
        verifyNoInteractions(identityRoleService);
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

    private Station station(UUID stationId) {
        Station station = new Station();
        station.setId(stationId);
        station.setName("ChargeOps Station");
        return station;
    }
}
