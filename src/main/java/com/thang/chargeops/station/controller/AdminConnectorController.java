package com.thang.chargeops.station.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.station.dto.chargepoint.request.ProvisionConnectorRequest;
import com.thang.chargeops.station.dto.chargepoint.request.UpdateConnectorRequest;
import com.thang.chargeops.station.dto.chargepoint.response.ConnectorDetailResponse;
import com.thang.chargeops.station.dto.chargepoint.response.ConnectorStatusEventResponse;
import com.thang.chargeops.station.service.ConnectorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "admin/stations/{stationId}/charge-points/{chargePointId}/connectors")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminConnectorController {

    private final ConnectorService connectorService;

    @GetMapping
    public ResponseEntity<ApiResult<List<ConnectorDetailResponse>>> list(
            @PathVariable UUID stationId,
            @PathVariable UUID chargePointId
    ) {
        return ResponseEntity.ok(ApiResult.success(
                connectorService.listForAdmin(stationId, chargePointId)
        ));
    }

    @GetMapping("/{connectorId}")
    public ResponseEntity<ApiResult<ConnectorDetailResponse>> getDetailConnector(
            @PathVariable UUID stationId,
            @PathVariable UUID chargePointId,
            @PathVariable UUID connectorId
    ) {
        return ResponseEntity.ok(ApiResult.success(
                connectorService.getDetailForAdmin(stationId, chargePointId, connectorId)
        ));
    }

    @GetMapping("/{connectorId}/status-history")
    public ResponseEntity<ApiResult<List<ConnectorStatusEventResponse>>> getStatusHistory(
            @PathVariable UUID stationId,
            @PathVariable UUID chargePointId,
            @PathVariable UUID connectorId
    ) {
        return ResponseEntity.ok(ApiResult.success(
                connectorService.getStatusHistoryForAdmin(stationId, chargePointId, connectorId)
        ));
    }

    @PostMapping
    public ResponseEntity<ApiResult<ConnectorDetailResponse>> provision(
            @PathVariable UUID stationId,
            @PathVariable UUID chargePointId,
            @Valid @RequestBody ProvisionConnectorRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.success(
                connectorService.provision(stationId, chargePointId, request)
        ));
    }

    @PatchMapping("/{connectorId}")
    public ResponseEntity<ApiResult<ConnectorDetailResponse>> update(
            @PathVariable UUID stationId,
            @PathVariable UUID chargePointId,
            @PathVariable UUID connectorId,
            @Valid @RequestBody UpdateConnectorRequest request
    ) {
        return ResponseEntity.ok(ApiResult.success(
                connectorService.updateForAdmin(stationId, chargePointId, connectorId, request)
        ));
    }

    @DeleteMapping("/{connectorId}")
    public ResponseEntity<Void> deleteDraft(
            @PathVariable UUID stationId,
            @PathVariable UUID chargePointId,
            @PathVariable UUID connectorId
    ) {
        connectorService.deleteDraft(stationId, chargePointId, connectorId);
        return ResponseEntity.noContent().build();
    }
}
