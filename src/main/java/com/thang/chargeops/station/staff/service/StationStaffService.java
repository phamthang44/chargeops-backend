package com.thang.chargeops.station.staff.service;

import com.thang.chargeops.station.staff.dto.CurrentStaffContextResponse;
import com.thang.chargeops.station.staff.dto.StaffLookupResponse;
import com.thang.chargeops.station.staff.dto.StationStaffResponse;
import com.thang.chargeops.station.staff.entity.StaffAssignmentStatus;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface StationStaffService {
    StaffLookupResponse lookUpEmail(String email, UUID stationId);

    Page<StationStaffResponse> getMyStaffs(
            UUID stationId,
            int pageNo,
            int pageSize,
            StaffAssignmentStatus assignmentStatus
    );

    StationStaffResponse assignStaff(UUID stationId, String email, String note);

    StationStaffResponse revokeStaff(UUID stationId, UUID assignmentId);

    CurrentStaffContextResponse getCurrentStaffContext();

}
