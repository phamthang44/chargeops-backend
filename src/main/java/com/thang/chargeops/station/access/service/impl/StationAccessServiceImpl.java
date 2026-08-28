package com.thang.chargeops.station.access.service.impl;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.access.service.StationAccessService;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.staff.entity.StaffAssignmentStatus;
import com.thang.chargeops.station.staff.repository.StationStaffAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StationAccessServiceImpl implements StationAccessService {

    private final StationRepository stationRepository;
    private final StationStaffAssignmentRepository assignmentRepository;
    private final CurrentProfileProvider currentProfileProvider;

    @Override
    public Station requireOwnerOrActiveStaff(UUID stationId) {
        Station station = requireStation(stationId);
        UUID currentProfileId = currentProfileProvider.requireProfileId();

        if (Objects.equals(station.getOwner().getId(), currentProfileId)
                || hasActiveStaffAssignment(stationId, currentProfileId)) {
            return station;
        }

        throw new AppException(StationErrorCode.STATION_ACCESS_DENIED);
    }

    @Override
    public Station requireActiveStaff(UUID stationId) {
        Station station = requireStation(stationId);
        UUID currentProfileId = currentProfileProvider.requireProfileId();

        if (hasActiveStaffAssignment(stationId, currentProfileId)) {
            return station;
        }

        throw new AppException(StationErrorCode.STATION_ACCESS_DENIED);
    }

    private Station requireStation(UUID stationId) {
        return stationRepository.findById(stationId)
                .orElseThrow(() -> new AppException(
                        StationErrorCode.STATION_NOT_FOUND,
                        stationId
                ));
    }

    private boolean hasActiveStaffAssignment(UUID stationId, UUID profileId) {
        return assignmentRepository.existsByStation_IdAndStaff_IdAndStatus(
                stationId,
                profileId,
                StaffAssignmentStatus.ACTIVE
        );
    }
}
