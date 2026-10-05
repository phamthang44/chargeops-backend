package com.thang.chargeops.station.staff.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.station.staff.entity.StaffAssignmentStatus;
import com.thang.chargeops.station.staff.service.StationStaffService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping(SystemConstant.API_URL_PATTERN + "owner/staffs")
@PreAuthorize("hasRole('OWNER')")
/*
 * SECURITY NOTE(staff-access): Quản lý Staff cấp Owner luôn là OWNER-only.
 * Endpoint này gom toàn bộ nhân viên thuộc tất cả các trạm mà Owner sở hữu,
 * tránh N+1 network fan-out từ client.
 */
public class OwnerStaffController {

    private final StationStaffService stationStaffService;

    @GetMapping
    public ResponseEntity<ApiResult<?>> listOwnerStaff(
            @RequestParam(required = false) UUID stationId,
            @RequestParam(defaultValue = "1") @Min(1) int pageNo,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) StaffAssignmentStatus assignmentStatus
    ) {
        return ResponseEntity.ok(ApiResult.successPage(
                stationStaffService.listAllOwnerStaff(stationId, pageNo, pageSize, assignmentStatus)
        ));
    }
}
