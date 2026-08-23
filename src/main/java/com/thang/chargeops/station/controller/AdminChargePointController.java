package com.thang.chargeops.station.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.station.dto.chargepoint.request.ActivateChargePointRequest;
import com.thang.chargeops.station.dto.chargepoint.request.ProvisionChargePointRequest;
import com.thang.chargeops.station.dto.chargepoint.request.UpdateChargePointRequest;
import com.thang.chargeops.station.dto.station.request.StationStatusChangeRequest;
import com.thang.chargeops.station.dto.chargepoint.response.ChargePointDetailResponse;
import com.thang.chargeops.station.dto.chargepoint.response.ChargePointStatusEventResponse;
import com.thang.chargeops.station.service.ChargePointService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "admin/stations/{stationId}/charge-points")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminChargePointController {

    private final ChargePointService chargePointService;

    @PostMapping
    public ResponseEntity<ApiResult<ChargePointDetailResponse>> provision(
            @PathVariable UUID stationId,
            @Valid @RequestBody ProvisionChargePointRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success(chargePointService.provision(stationId, request)));
    }

    @GetMapping
    public ResponseEntity<ApiResult<List<ChargePointDetailResponse>>> list(
            @PathVariable UUID stationId
    ) {
        return ResponseEntity.ok(ApiResult.success(chargePointService.listForAdmin(stationId)));
    }

    @GetMapping("/{chargePointId}")
    public ResponseEntity<ApiResult<ChargePointDetailResponse>> getChargePointDetail(
            @PathVariable UUID stationId,
            @PathVariable UUID chargePointId
    ) {
        return ResponseEntity.ok(ApiResult.success(chargePointService.getDetailForAdmin(stationId, chargePointId)));
    }

    @GetMapping("/{chargePointId}/status-history")
    public ResponseEntity<ApiResult<List<ChargePointStatusEventResponse>>> getStatusHistory(
            @PathVariable UUID stationId,
            @PathVariable UUID chargePointId
    ) {
        return ResponseEntity.ok(ApiResult.success(
                chargePointService.getStatusHistoryForAdmin(stationId, chargePointId)
        ));
    }

    @PatchMapping("/{chargePointId}")
    public ResponseEntity<ApiResult<ChargePointDetailResponse>> update(
            @PathVariable UUID stationId,
            @PathVariable UUID chargePointId,
            @Valid @RequestBody UpdateChargePointRequest request
    ) {
        return ResponseEntity.ok(ApiResult.success(
                chargePointService.update(stationId, chargePointId, request)
        ));
    }

    @DeleteMapping("/{chargePointId}")
    public ResponseEntity<Void> deleteDraft(
            @PathVariable UUID stationId,
            @PathVariable UUID chargePointId
    ) {
        chargePointService.deleteDraft(stationId, chargePointId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{chargePointId}/activate")
    public ResponseEntity<ApiResult<ChargePointDetailResponse>> activate(
            @PathVariable UUID stationId,
            @PathVariable UUID chargePointId,
            @Valid @RequestBody ActivateChargePointRequest request
    ) {
        return ResponseEntity.ok(ApiResult.success(
                chargePointService.activate(stationId, chargePointId, request)
        ));
    }

    @PostMapping("/{chargePointId}/suspend")
    public ResponseEntity<ApiResult<ChargePointDetailResponse>> suspend(
            @PathVariable UUID stationId,
            @PathVariable UUID chargePointId,
            @Valid @RequestBody StationStatusChangeRequest request
    ) {
        return ResponseEntity.ok(ApiResult.success(
                chargePointService.suspend(stationId, chargePointId, request.reason())
        ));
    }

    @PostMapping("/{chargePointId}/reactivate")
    public ResponseEntity<ApiResult<ChargePointDetailResponse>> reactivate(
            @PathVariable UUID stationId,
            @PathVariable UUID chargePointId,
            @Valid @RequestBody StationStatusChangeRequest request
    ) {
        return ResponseEntity.ok(ApiResult.success(
                chargePointService.reactivate(stationId, chargePointId, request.reason())
        ));
    }
}
