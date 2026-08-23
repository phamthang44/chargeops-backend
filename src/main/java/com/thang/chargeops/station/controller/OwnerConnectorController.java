package com.thang.chargeops.station.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.station.dto.chargepoint.request.ChangeRuntimeStatusRequest;
import com.thang.chargeops.station.dto.chargepoint.response.ConnectorDetailResponse;
import com.thang.chargeops.station.dto.chargepoint.response.ConnectorStatusEventResponse;
import com.thang.chargeops.station.service.ConnectorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "owner/stations/{stationId}/charge-points/{chargePointId}/connectors")
@RequiredArgsConstructor
@PreAuthorize("hasRole('OWNER')")
public class OwnerConnectorController {

    private final ConnectorService connectorService;

    @GetMapping
    public ResponseEntity<ApiResult<List<ConnectorDetailResponse>>> list(
            @PathVariable UUID stationId,
            @PathVariable UUID chargePointId
    ) {
        return ResponseEntity.ok(ApiResult.success(
                connectorService.listForCurrentOwner(stationId, chargePointId)
        ));
    }

    @GetMapping("/{connectorId}/status-history")
    public ResponseEntity<ApiResult<List<ConnectorStatusEventResponse>>> getStatusHistory(
            @PathVariable UUID stationId,
            @PathVariable UUID chargePointId,
            @PathVariable UUID connectorId
    ) {
        return ResponseEntity.ok(ApiResult.success(
                connectorService.getStatusHistoryForCurrentOwner(stationId, chargePointId, connectorId)
        ));
    }

    @PatchMapping("/{connectorId}/runtime-status")
    public ResponseEntity<ApiResult<ConnectorDetailResponse>> changeRuntimeStatus(
            @PathVariable UUID stationId,
            @PathVariable UUID chargePointId,
            @PathVariable UUID connectorId,
            @Valid @RequestBody ChangeRuntimeStatusRequest request
    ) {
        return ResponseEntity.ok(ApiResult.success(
                connectorService.changeRuntimeStatusForCurrentOwner(
                        stationId,
                        chargePointId,
                        connectorId,
                        request
                )
        ));
    }
}
