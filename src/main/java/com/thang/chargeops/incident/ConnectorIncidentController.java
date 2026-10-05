package com.thang.chargeops.incident;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "stations/{stationId}")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class ConnectorIncidentController {
    private final ConnectorIncidentService service;
    @PostMapping("/connectors/{connectorId}/incidents")
    public ResponseEntity<ApiResult<IncidentResponse>> report(@PathVariable UUID stationId, @PathVariable UUID connectorId,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody IncidentRequests.Report request) {
        return ok(service.report(stationId, connectorId, key, request));
    }
    @GetMapping("/incidents/{incidentId}")
    public ResponseEntity<ApiResult<IncidentResponse>> get(@PathVariable UUID stationId, @PathVariable UUID incidentId) {
        return ok(service.get(stationId, incidentId));
    }
    @PostMapping("/incidents/{incidentId}/recover")
    public ResponseEntity<ApiResult<IncidentResponse>> recover(@PathVariable UUID stationId, @PathVariable UUID incidentId,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody IncidentRequests.Recover request) {
        return ok(service.recover(stationId, incidentId, key, request));
    }
    @PostMapping("/incidents/{incidentId}/bookings/{bookingId}/resolve-session")
    public ResponseEntity<ApiResult<IncidentResponse>> resolve(@PathVariable UUID stationId, @PathVariable UUID incidentId,
            @PathVariable UUID bookingId, @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody IncidentRequests.ResolveSession request) {
        return ok(service.resolveSession(stationId, incidentId, bookingId, key, request));
    }
    private static ResponseEntity<ApiResult<IncidentResponse>> ok(IncidentResponse response) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResult.success(response));
    }
}
