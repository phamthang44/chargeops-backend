package com.thang.chargeops.station.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.station.dto.station.request.RejectStationRequest;
import com.thang.chargeops.station.service.StationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "admin/station-approvals")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminStationApprovalController {

    private final StationService stationService;

    @GetMapping
    public ResponseEntity<ApiResult<?>> getListOfRequiredApprovalStations(
            @Min(1) @RequestParam(defaultValue = "1") int pageNo,
            @Min(1) @RequestParam(defaultValue = "20") int pageSize) {

        var response = stationService.getStationApprovals(pageNo, pageSize);

        return ResponseEntity.status(HttpStatus.OK).body(ApiResult.successPage(response));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResult<?>> getStationApprovalById(@PathVariable UUID id) {
        var response = stationService.getStationApprovalDetail(id);
        return ResponseEntity.ok(ApiResult.success(response, "Retrieve approval ID : | " + id + "| successfully"));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<ApiResult<?>> approveStationApproval(@PathVariable UUID id) {
        stationService.approveStation(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResult<?>> rejectStationApproval(@PathVariable UUID id, @Valid @RequestBody RejectStationRequest request) {
        stationService.rejectStation(id, request);
        return ResponseEntity.noContent().build();
    }
}
