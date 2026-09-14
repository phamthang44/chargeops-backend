package com.thang.chargeops.station.staff.service.impl;

import com.thang.chargeops.common.enums.Role;
import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
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
import com.thang.chargeops.station.staff.service.StationStaffService;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StationStaffServiceImpl implements StationStaffService {

    private static final String ACTIVE_ASSIGNMENT_UNIQUE_CONSTRAINT =
            "uq_station_staff_assignments_one_active_per_user";

    private final StationStaffAssignmentRepository stationStaffAssignmentRepository;
    private final UserProfileService userProfileService;
    private final StationRepository stationRepository;
    private final CurrentProfileProvider currentProfileProvider;
    private final StationStaffPolicy policy;
    private final IdentityRoleService identityRoleService;

    @Override
    @Transactional(readOnly = true)
    public StaffLookupResponse lookUpEmail(String email, UUID stationId) {
        Station station = stationRepository.findById(stationId)
                .orElseThrow(() -> new AppException(StationErrorCode.STATION_NOT_FOUND, stationId));
        UUID currentOwnerId = currentProfileProvider.requireProfileId();
        policy.requireOwnerCanManageStaff(station, currentOwnerId);

        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        UserProfile candidate = userProfileService.getUserProfileByEmail(normalizedEmail);

        if (candidate == null) {
            return new StaffLookupResponse(
                    false,
                    normalizedEmail,
                    null,
                    null,
                    false,
                    StaffLookupStatus.NOT_FOUND
            );
        }

        StaffLookupStatus status = evaluateLookupStatus(candidate, currentOwnerId);
        return buildLookUpResponse(candidate, status);
    }

    private StaffLookupStatus evaluateLookupStatus(UserProfile candidate, UUID currentOwnerId) {
        if (candidate.getId().equals(currentOwnerId)) {
            return StaffLookupStatus.SELF_ASSIGNMENT;
        }

        if (candidate.getStatus() != UserStatus.ACTIVE) {
            return StaffLookupStatus.ACCOUNT_INACTIVE;
        }

        if (stationStaffAssignmentRepository.existsByStaff_IdAndStatus(candidate.getId(), StaffAssignmentStatus.ACTIVE)) {
            return StaffLookupStatus.ALREADY_ASSIGNED;
        }

        Set<Role> roles = identityRoleService.getRoles(candidate.getKeycloakId());
        if (!roles.contains(Role.DRIVER)
                || roles.contains(Role.OWNER)
                || roles.contains(Role.ADMIN)) {
            return StaffLookupStatus.ROLE_NOT_ALLOWED;
        }

        return StaffLookupStatus.ELIGIBLE;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StationStaffResponse> listStationStaff(
            UUID stationId,
            int pageNo,
            int pageSize,
            StaffAssignmentStatus assignmentStatus) {
        int pageIndex = Math.max(pageNo - 1, 0);
        Pageable pageable = PageRequest.of(pageIndex, pageSize);

        UUID currentOwnerId = currentProfileProvider.requireProfileId();
        Station station = stationRepository.findById(stationId).orElseThrow(() -> new AppException(StationErrorCode.STATION_NOT_FOUND, stationId));

        policy.requireOwnerCanManageStaff(station, currentOwnerId);

        Page<StationStaffAssignment> staffAssignments =
                stationStaffAssignmentRepository.findAllByStation_IdAndStatus(
                        stationId,
                        assignmentStatus == null
                                ? StaffAssignmentStatus.ACTIVE
                                : assignmentStatus,
                        pageable
                );

        return staffAssignments.map(this::convertToResponse);
    }

    @Override
    @Transactional
    public StationStaffResponse assignStaff(UUID stationId, String email, String note) {
        UUID currentOwnerId = currentProfileProvider.requireProfileId();
        Station station = requireStation(stationId);
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        UserProfile candidate = userProfileService.getUserProfileByEmail(normalizedEmail);
        if (candidate == null) {
            throw new AppException(StationStaffErrorCode.CANDIDATE_NOT_FOUND);
        }

        Set<Role> roles = identityRoleService.getRoles(candidate.getKeycloakId());
        boolean hasActiveAssignment =
                stationStaffAssignmentRepository.existsByStaff_IdAndStatus(
                        candidate.getId(),
                        StaffAssignmentStatus.ACTIVE
                );
        policy.requireCanAssignStaff(
                station,
                currentOwnerId,
                candidate,
                roles,
                hasActiveAssignment
        );

        StationStaffAssignment assignment = StationStaffAssignment.builder()
                .station(station)
                .staff(candidate)
                .status(StaffAssignmentStatus.ACTIVE)
                .note(normalizeOptionalNote(note))
                .assignedBy(currentOwnerId)
                .assignedAt(Instant.now())
                .build();

        StationStaffAssignment savedAssignment;
        try {
            savedAssignment = stationStaffAssignmentRepository.saveAndFlush(assignment);
        } catch (DataIntegrityViolationException exception) {
            if (isActiveAssignmentUniqueConstraintViolation(exception)) {
                throw new AppException(
                        StationStaffErrorCode.ACTIVE_ASSIGNMENT_ALREADY_EXISTS
                );
            }
            throw exception;
        }

        return convertToResponse(savedAssignment);
    }

    @Override
    @Transactional
    public StationStaffResponse revokeStaff(UUID stationId, UUID assignmentId) {
        UUID currentOwnerId = currentProfileProvider.requireProfileId();
        StationStaffAssignment assignment =
                stationStaffAssignmentRepository.findByIdAndStation_Id(
                                assignmentId,
                                stationId
                        )
                        .orElseThrow(() -> new AppException(
                                StationStaffErrorCode.ASSIGNMENT_NOT_FOUND
                        ));

        policy.requireCanRevokeStaff(assignment, stationId, currentOwnerId);

        assignment.setStatus(StaffAssignmentStatus.REVOKED);
        assignment.setRevokedBy(currentOwnerId);
        assignment.setRevokedAt(Instant.now());
        StationStaffAssignment savedAssignment =
                stationStaffAssignmentRepository.saveAndFlush(assignment);

        return convertToResponse(savedAssignment);
    }

    @Override
    @Transactional(readOnly = true)
    public CurrentStaffContextResponse getCurrentStaffContext() {
        UUID currentProfileId = currentProfileProvider.requireProfileId();

        return stationStaffAssignmentRepository.findByStaff_IdAndStatus(
                        currentProfileId,
                        StaffAssignmentStatus.ACTIVE
                )
                .map(this::convertToCurrentStaffContext)
                .orElseGet(CurrentStaffContextResponse::notAssigned);
    }

    private StaffLookupResponse buildLookUpResponse(UserProfile userProfile, StaffLookupStatus status) {
        return new StaffLookupResponse(
                true,
                userProfile.getEmail(),
                userProfile.getDisplayName(),
                maskPhoneNumber(userProfile.getPhone()),
                status == StaffLookupStatus.ELIGIBLE,
                status
        );
    }

    private StationStaffResponse convertToResponse(StationStaffAssignment entity) {
        return StationStaffResponse.builder()
                .assignmentId(entity.getId())
                .stationId(entity.getStation().getId())
                .stationName(entity.getStation().getName())
                .userId(entity.getStaff().getId())
                .note(entity.getNote())
                .assignedAt(entity.getAssignedAt())
                .assignedBy(entity.getAssignedBy())
                .email(entity.getStaff().getEmail())
                .displayName(entity.getStaff().getDisplayName())
                .maskedPhone(maskPhoneNumber(entity.getStaff().getPhone()))
                .revokedAt(entity.getRevokedAt())
                .revokedBy(entity.getRevokedBy())
                .status(entity.getStatus())
                .build();
    }

    private CurrentStaffContextResponse convertToCurrentStaffContext(
            StationStaffAssignment assignment) {
        Station station = assignment.getStation();
        return new CurrentStaffContextResponse(
                true,
                assignment.getId(),
                assignment.getStatus(),
                new CurrentStaffContextResponse.StationSummary(
                        station.getId(),
                        station.getStationCode(),
                        station.getName()
                )
        );
    }

    private String maskPhoneNumber(String phone) {
        if (phone == null || phone.length() < 7) {
            return null;
        }

        int suffixStart = phone.length() - 3;
        int maskStart = suffixStart - 4;

        return phone.substring(0, maskStart)
                + "****"
                + phone.substring(suffixStart);
    }

    private Station requireStation(UUID stationId) {
        return stationRepository.findById(stationId)
                .orElseThrow(() -> new AppException(
                        StationErrorCode.STATION_NOT_FOUND,
                        stationId
                ));
    }

    private String normalizeOptionalNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        return note.trim();
    }

    private boolean isActiveAssignmentUniqueConstraintViolation(
            DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException constraintViolationException) {
                String constraintName = constraintViolationException.getConstraintName();
                if (ACTIVE_ASSIGNMENT_UNIQUE_CONSTRAINT.equalsIgnoreCase(constraintName)) {
                    return true;
                }
            }

            String message = cause.getMessage();
            if (message != null
                    && message.toLowerCase(Locale.ROOT)
                    .contains(ACTIVE_ASSIGNMENT_UNIQUE_CONSTRAINT)) {
                return true;
            }

            Throwable nextCause = cause.getCause();
            if (nextCause == cause) {
                break;
            }
            cause = nextCause;
        }
        return false;
    }

}
