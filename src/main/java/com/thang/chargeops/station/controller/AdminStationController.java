package com.thang.chargeops.station.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.exception.errormessage.StationErrorMessage;
import com.thang.chargeops.station.dto.station.filter.StationFilter;
import com.thang.chargeops.station.dto.station.request.StationStatusChangeRequest;
import com.thang.chargeops.station.dto.station.response.AdminStationDetailResponse;
import com.thang.chargeops.station.dto.station.response.AdminStationListItemResponse;
import com.thang.chargeops.station.service.StationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "admin/stations")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminStationController {

    private final StationService stationService;

    @GetMapping
    public ResponseEntity<ApiResult<?>> getStations(
            @ModelAttribute StationFilter filter,
            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = StationErrorMessage.PAGE_NUMBER_MIN_KEY) int pageNo,
            @RequestParam(defaultValue = "8")
            @Min(value = 1, message = StationErrorMessage.PAGE_SIZE_MIN_KEY)
            @Max(value = 100, message = StationErrorMessage.PAGE_SIZE_MAX_KEY) int pageSize
    ) {
        Page<AdminStationListItemResponse> page = stationService.getAdminStations(pageNo, pageSize, filter);
        return ResponseEntity.ok(ApiResult.successPage(page));
    }

    @GetMapping("/{stationId}")
    public ResponseEntity<ApiResult<AdminStationDetailResponse>> getStationDetail(
            @PathVariable UUID stationId
    ) {
        AdminStationDetailResponse detail = stationService.getAdminStationDetail(stationId);
        return ResponseEntity.ok(ApiResult.success(detail));
    }

    @PostMapping("/{stationId}/suspend")
    public ResponseEntity<Void> suspendStation(
            @PathVariable UUID stationId,
            @Valid @RequestBody StationStatusChangeRequest request
    ) {
        stationService.suspendStation(stationId, request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{stationId}/reactivate")
    public ResponseEntity<Void> reactivateStation(
            @PathVariable UUID stationId,
            @Valid @RequestBody StationStatusChangeRequest request
    ) {
        stationService.reactivateStation(stationId, request.reason());
        return ResponseEntity.noContent().build();
    }


}
