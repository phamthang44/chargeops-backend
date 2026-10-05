package com.thang.chargeops.station.staff.controller;

import com.thang.chargeops.station.staff.service.StaffStationOperationService;

import com.thang.chargeops.booking.dto.filter.StationOperationalBookingFilter;
import com.thang.chargeops.booking.dto.response.OperationalBookingResponse;
import com.thang.chargeops.booking.service.StationOperationalBookingService;
import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.station.staff.dto.StaffChargePointStatusRequest;
import com.thang.chargeops.station.staff.dto.StaffConnectorStatusRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.thang.chargeops.station.staff.dto.StaffOperationResponses.*;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping(SystemConstant.API_URL_PATTERN + "staff/stations")
@PreAuthorize("hasRole('STAFF')")
public class StaffStationOperationController {
    private final StaffStationOperationService service;
    private final StationOperationalBookingService bookings;

    @GetMapping("/{stationId}")
    public ResponseEntity<ApiResult<Overview>> getStationOperationalOverview(@PathVariable UUID stationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.success(service.overview(stationId)));
    }

    @GetMapping("/{stationId}/bookings")
    public ResponseEntity<ApiResult<List<OperationalBookingResponse>>> listStationBookings(
            @PathVariable UUID stationId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) UUID connectorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        service.overview(stationId);
        Page<OperationalBookingResponse> result = bookings.getOperationalBookings(
                stationId, new StationOperationalBookingFilter(connectorId, from, to), page, size);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResult.successPage(result));
    }

    @GetMapping("/{stationId}/charge-points")
    public ResponseEntity<ApiResult<List<ChargePointItem>>> listChargePoints(@PathVariable UUID stationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.success(service.chargePoints(stationId)));
    }

    @GetMapping("/{stationId}/charge-points/{chargePointId}/status-history")
    public ResponseEntity<ApiResult<List<HistoryItem>>> getChargePointStatusHistory(
            @PathVariable UUID stationId, @PathVariable UUID chargePointId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.successPage(service.chargePointHistory(stationId, chargePointId, page, size)));
    }

    @PatchMapping("/{stationId}/charge-points/{chargePointId}/operational-status")
    public ResponseEntity<ApiResult<ChargePointItem>> changeChargePointOperationalStatus(
            @PathVariable UUID stationId, @PathVariable UUID chargePointId,
            @Valid @RequestBody StaffChargePointStatusRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.success(service.changeChargePoint(stationId, chargePointId, request)));
    }

    @GetMapping("/{stationId}/charge-points/{chargePointId}/connectors")
    public ResponseEntity<ApiResult<List<ConnectorItem>>> listConnectors(
            @PathVariable UUID stationId, @PathVariable UUID chargePointId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.success(service.connectors(stationId, chargePointId)));
    }

    @GetMapping("/{stationId}/charge-points/{chargePointId}/connectors/{connectorId}/status-history")
    public ResponseEntity<ApiResult<List<HistoryItem>>> getConnectorStatusHistory(
            @PathVariable UUID stationId, @PathVariable UUID chargePointId, @PathVariable UUID connectorId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.successPage(service.connectorHistory(stationId, chargePointId, connectorId, page, size)));
    }

    @PatchMapping("/{stationId}/charge-points/{chargePointId}/connectors/{connectorId}/runtime-status")
    public ResponseEntity<ApiResult<ConnectorItem>> changeConnectorRuntimeStatus(
            @PathVariable UUID stationId, @PathVariable UUID chargePointId, @PathVariable UUID connectorId,
            @Valid @RequestBody StaffConnectorStatusRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.success(service.changeConnector(stationId, chargePointId, connectorId, request)));
    }

    /** Physical emergency stop needs an OCPP command/acknowledgement contract. */
    @PostMapping("/{stationId}/charge-points/{chargePointId}/connectors/{connectorId}/emergency-stops")
    public ResponseEntity<ApiResult<?>> createEmergencyStop(
            @PathVariable UUID stationId, @PathVariable UUID chargePointId, @PathVariable UUID connectorId) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
