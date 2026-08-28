package com.thang.chargeops.station.staff.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.station.staff.dto.CurrentStaffContextResponse;
import com.thang.chargeops.station.staff.service.StationStaffService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping(SystemConstant.API_URL_PATTERN + "me/staff-context")
@PreAuthorize("isAuthenticated()")
public class CurrentStationStaffController {

    private final StationStaffService stationStaffService;

    @GetMapping
    public ResponseEntity<ApiResult<CurrentStaffContextResponse>> getCurrentStaffContext() {
        return ResponseEntity.ok(ApiResult.success(
                stationStaffService.getCurrentStaffContext()
        ));
    }
}
