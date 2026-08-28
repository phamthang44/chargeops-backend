package com.thang.chargeops.station.staff.policy;

import com.thang.chargeops.common.enums.Role;
import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.BaseErrorCode;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.exception.errorcode.StationStaffErrorCode;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.staff.entity.StaffAssignmentStatus;
import com.thang.chargeops.station.staff.entity.StationStaffAssignment;
import com.thang.chargeops.station.staff.policy.impl.StationStaffPolicyImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StationStaffPolicyImplTest {

    private StationStaffPolicyImpl policy;
    private UUID ownerId;
    private Station station;

    @BeforeEach
    void setUp() {
        policy = new StationStaffPolicyImpl();
        ownerId = UUID.randomUUID();
        station = station(ownerId, StationStatus.ACTIVE);
    }

    @Test
    void requireOwnerCanManageStaffAllowsTheStationOwner() {
        assertThatCode(() -> policy.requireOwnerCanManageStaff(station, ownerId))
                .doesNotThrowAnyException();
    }

    @Test
    void requireOwnerCanManageStaffRejectsAnotherOwner() {
        assertError(
                () -> policy.requireOwnerCanManageStaff(station, UUID.randomUUID()),
                StationErrorCode.STATION_ACCESS_DENIED
        );
    }

    @Test
    void requireCanAssignStaffAllowsAnActiveDriverWithoutAssignment() {
        UserProfile candidate = profile(UUID.randomUUID(), UserStatus.ACTIVE);

        assertThatCode(() -> policy.requireCanAssignStaff(
                station,
                ownerId,
                candidate,
                Set.of(Role.DRIVER),
                false
        )).doesNotThrowAnyException();
    }

    @Test
    void requireCanAssignStaffRejectsInactiveStation() {
        station.setStatus(StationStatus.SUSPENDED);

        assertAssignError(
                profile(UUID.randomUUID(), UserStatus.ACTIVE),
                Set.of(Role.DRIVER),
                false,
                StationStaffErrorCode.STATION_NOT_ACTIVE_FOR_ASSIGNMENT
        );
    }

    @Test
    void requireCanAssignStaffRejectsSelfAssignment() {
        assertAssignError(
                profile(ownerId, UserStatus.ACTIVE),
                Set.of(Role.DRIVER),
                false,
                StationStaffErrorCode.SELF_ASSIGNMENT_NOT_ALLOWED
        );
    }

    @Test
    void requireCanAssignStaffRejectsInactiveCandidate() {
        assertAssignError(
                profile(UUID.randomUUID(), UserStatus.SUSPENDED),
                Set.of(Role.DRIVER),
                false,
                StationStaffErrorCode.CANDIDATE_ACCOUNT_INACTIVE
        );
    }

    @Test
    void requireCanAssignStaffRejectsCandidateWithoutDriverRole() {
        assertAssignError(
                profile(UUID.randomUUID(), UserStatus.ACTIVE),
                Set.of(),
                false,
                StationStaffErrorCode.CANDIDATE_ROLE_NOT_ALLOWED
        );
    }

    @Test
    void requireCanAssignStaffRejectsPrivilegedCandidate() {
        assertAssignError(
                profile(UUID.randomUUID(), UserStatus.ACTIVE),
                Set.of(Role.DRIVER, Role.ADMIN),
                false,
                StationStaffErrorCode.CANDIDATE_ROLE_NOT_ALLOWED
        );
    }

    @Test
    void requireCanAssignStaffRejectsExistingActiveAssignment() {
        assertAssignError(
                profile(UUID.randomUUID(), UserStatus.ACTIVE),
                Set.of(Role.DRIVER),
                true,
                StationStaffErrorCode.ACTIVE_ASSIGNMENT_ALREADY_EXISTS
        );
    }

    @Test
    void requireCanRevokeStaffAllowsActiveAssignmentOwnedByRequester() {
        StationStaffAssignment assignment = assignment(station, StaffAssignmentStatus.ACTIVE);

        assertThatCode(() -> policy.requireCanRevokeStaff(
                assignment,
                station.getId(),
                ownerId
        )).doesNotThrowAnyException();
    }

    @Test
    void requireCanRevokeStaffRejectsAssignmentFromAnotherStation() {
        StationStaffAssignment assignment = assignment(station, StaffAssignmentStatus.ACTIVE);

        assertError(
                () -> policy.requireCanRevokeStaff(
                        assignment,
                        UUID.randomUUID(),
                        ownerId
                ),
                StationStaffErrorCode.ASSIGNMENT_NOT_FOUND
        );
    }

    @Test
    void requireCanRevokeStaffRejectsAnotherOwner() {
        StationStaffAssignment assignment = assignment(station, StaffAssignmentStatus.ACTIVE);

        assertError(
                () -> policy.requireCanRevokeStaff(
                        assignment,
                        station.getId(),
                        UUID.randomUUID()
                ),
                StationErrorCode.STATION_ACCESS_DENIED
        );
    }

    @Test
    void requireCanRevokeStaffRejectsAlreadyRevokedAssignment() {
        StationStaffAssignment assignment = assignment(station, StaffAssignmentStatus.REVOKED);

        assertError(
                () -> policy.requireCanRevokeStaff(
                        assignment,
                        station.getId(),
                        ownerId
                ),
                StationStaffErrorCode.ASSIGNMENT_ALREADY_REVOKED
        );
    }

    private void assertAssignError(
            UserProfile candidate,
            Set<Role> roles,
            boolean hasActiveAssignment,
            BaseErrorCode expectedError) {
        assertError(
                () -> policy.requireCanAssignStaff(
                        station,
                        ownerId,
                        candidate,
                        roles,
                        hasActiveAssignment
                ),
                expectedError
        );
    }

    private void assertError(Runnable command, BaseErrorCode expectedError) {
        assertThatThrownBy(command::run)
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expectedError)
                );
    }

    private Station station(UUID stationOwnerId, StationStatus status) {
        UserProfile owner = profile(stationOwnerId, UserStatus.ACTIVE);
        Station result = new Station();
        result.setId(UUID.randomUUID());
        result.setOwner(owner);
        result.setStatus(status);
        return result;
    }

    private UserProfile profile(UUID id, UserStatus status) {
        UserProfile profile = new UserProfile();
        profile.setId(id);
        profile.setStatus(status);
        return profile;
    }

    private StationStaffAssignment assignment(
            Station assignedStation,
            StaffAssignmentStatus status) {
        return StationStaffAssignment.builder()
                .station(assignedStation)
                .staff(profile(UUID.randomUUID(), UserStatus.ACTIVE))
                .status(status)
                .build();
    }
}
