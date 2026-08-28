package com.thang.chargeops.station.staff.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.station.staff.dto.AssignStationStaffRequest;
import com.thang.chargeops.station.staff.dto.StaffLookupResponse;
import com.thang.chargeops.station.staff.dto.StationStaffResponse;
import com.thang.chargeops.station.staff.entity.StaffAssignmentStatus;
import com.thang.chargeops.station.staff.service.StationStaffService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.thang.chargeops.common.constant.CommonConfig.MAX_LENGTH_EMAIL;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping(
        SystemConstant.API_URL_PATTERN
                + "owner/stations/{stationId}/staffs"
)
@PreAuthorize("hasRole('OWNER')")
/*
 * TODO(staff-access): Quản lý Staff (lookup, danh sách, assign, revoke) luôn là OWNER-only.
 * Không cho Staff tự quản lý assignment và không dùng requireOwnerOrActiveStaff() cho các API này.
 */
public class OwnerStationStaffController  {

    private final StationStaffService stationStaffService;

    @GetMapping("/lookup")
    public ResponseEntity<ApiResult<StaffLookupResponse>> lookupByEmail(
            @PathVariable UUID stationId,
            @RequestParam
            @NotBlank(message = "Staff email is required")
            @Email(message = "Staff email must be valid")
            @Size(max = MAX_LENGTH_EMAIL, message = "Staff email is too long")
            String email) {
        return ResponseEntity.ok(ApiResult.success(
                stationStaffService.lookUpEmail(email, stationId)
        ));
    }

    @GetMapping
    public ResponseEntity<ApiResult<?>> getMyStaffs(
            @PathVariable UUID stationId,

            @RequestParam(defaultValue = "1")
            @Min(1)
            int pageNo,

            @RequestParam(defaultValue = "10")
            @Min(1)
            @Max(100)
            int pageSize,

            @RequestParam(required = false)
            StaffAssignmentStatus assignmentStatus
    ) {
        return ResponseEntity.ok(ApiResult.successPage(stationStaffService.getMyStaffs(stationId, pageNo, pageSize, assignmentStatus)));
    }

    @PostMapping
    public ResponseEntity<ApiResult<StationStaffResponse>> assignStationStaff(
            @PathVariable UUID stationId,
            @Valid @RequestBody AssignStationStaffRequest request) {
        StationStaffResponse response = stationStaffService.assignStaff(
                stationId,
                request.email(),
                request.note()
        );
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success(response, "Staff assigned successfully"));
    }

    @DeleteMapping("/{assignmentId}")
    public ResponseEntity<ApiResult<StationStaffResponse>> revokeStationStaff(
            @PathVariable UUID stationId,
            @PathVariable UUID assignmentId) {
        return ResponseEntity.ok(ApiResult.success(
                stationStaffService.revokeStaff(stationId, assignmentId),
                "Staff assignment revoked successfully"
        ));
    }
}
