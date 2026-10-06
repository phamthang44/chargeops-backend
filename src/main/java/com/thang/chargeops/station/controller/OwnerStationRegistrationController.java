package com.thang.chargeops.station.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.station.dto.station.request.RegisterStationRequest;
import com.thang.chargeops.station.dto.station.response.OwnerStationRegistrationResponse;
import com.thang.chargeops.station.service.usecase.OwnerStationRegistrationUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "owner/stations/{stationId}/registration")
@RequiredArgsConstructor
@PreAuthorize("hasRole('OWNER')")
public class OwnerStationRegistrationController {
    private final OwnerStationRegistrationUseCase registrationUseCase;

    @GetMapping
    public ResponseEntity<ApiResult<OwnerStationRegistrationResponse>> get(@PathVariable UUID stationId) {
        return ResponseEntity.ok(ApiResult.success(registrationUseCase.get(stationId)));
    }

    @PutMapping
    public ResponseEntity<ApiResult<OwnerStationRegistrationResponse>> update(
            @PathVariable UUID stationId, @RequestParam @Min(0) long version,
            @Valid @RequestBody RegisterStationRequest request) {
        return ResponseEntity.ok(ApiResult.success(registrationUseCase.update(stationId, version, request)));
    }

    @DeleteMapping
    public ResponseEntity<Void> withdraw(@PathVariable UUID stationId, @RequestParam @Min(0) long version) {
        registrationUseCase.withdraw(stationId, version);
        return ResponseEntity.noContent().build();
    }
}
