package com.thang.chargeops.station.staff.policy.impl;

import com.thang.chargeops.common.enums.Role;
import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.exception.errorcode.StationStaffErrorCode;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.staff.entity.StaffAssignmentStatus;
import com.thang.chargeops.station.staff.entity.StationStaffAssignment;
import com.thang.chargeops.station.staff.policy.StationStaffPolicy;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Component
public class StationStaffPolicyImpl implements StationStaffPolicy {

    @Override
    public void requireOwnerCanManageStaff(Station station, UUID currentOwnerProfileId) {
        if (!Objects.equals(station.getOwner().getId(), currentOwnerProfileId)) {
            throw new AppException(StationErrorCode.STATION_ACCESS_DENIED);
        }
    }

    @Override
    public void requireCanAssignStaff(
            Station station,
            UUID currentOwnerProfileId,
            UserProfile candidate,
            Set<Role> candidateRoles,
            boolean hasActiveAssignment) {
        requireOwnerCanManageStaff(station, currentOwnerProfileId);

        if (station.getStatus() != StationStatus.ACTIVE) {
            throw new AppException(StationStaffErrorCode.STATION_NOT_ACTIVE_FOR_ASSIGNMENT);
        }

        if (Objects.equals(candidate.getId(), currentOwnerProfileId)) {
            throw new AppException(
                    StationStaffErrorCode.SELF_ASSIGNMENT_NOT_ALLOWED
            );
        }

        if (candidate.getStatus() != UserStatus.ACTIVE) {
            throw new AppException(StationStaffErrorCode.CANDIDATE_ACCOUNT_INACTIVE);
        }

        if (!candidateRoles.contains(Role.DRIVER)
                || candidateRoles.contains(Role.OWNER)
                || candidateRoles.contains(Role.ADMIN)) {
            throw new AppException(
                    StationStaffErrorCode.CANDIDATE_ROLE_NOT_ALLOWED
            );
        }

        if (hasActiveAssignment) {
            throw new AppException(
                    StationStaffErrorCode.ACTIVE_ASSIGNMENT_ALREADY_EXISTS
            );
        }
    }

    @Override
    public void requireCanRevokeStaff(StationStaffAssignment assignment, UUID requestedStationId, UUID currentOwnerProfileId) {
        Station station = assignment.getStation();

        if (!Objects.equals(station.getId(), requestedStationId)) {
            throw new AppException(
                    StationStaffErrorCode.ASSIGNMENT_NOT_FOUND
            );
        }

        requireOwnerCanManageStaff(station, currentOwnerProfileId);

        if (assignment.getStatus() != StaffAssignmentStatus.ACTIVE) {
            throw new AppException(
                    StationStaffErrorCode.ASSIGNMENT_ALREADY_REVOKED
            );
        }
    }
}
