package com.thang.chargeops.station.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.station.dto.station.request.RegisterStationRequest;
import com.thang.chargeops.station.dto.station.response.StationCreatedResponse;
import com.thang.chargeops.station.service.StationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "owner/stations")
@RequiredArgsConstructor
@PreAuthorize("hasRole('OWNER')")
public class OwnerStationController {

    private final StationService stationService;

    @PostMapping
    public ResponseEntity<ApiResult<?>> registerNewStation(@Valid @RequestBody RegisterStationRequest request) {
        StationCreatedResponse response = stationService.createStationRegistration(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.success(response));
    }

    @GetMapping("/mine")
    public ResponseEntity<ApiResult<?>> getMyStations(
                                                   @Min(1) @RequestParam(defaultValue = "1") int pageNo,
                                                   @Min(1) @RequestParam(defaultValue = "20") int pageSize) {
        var response = stationService.getMyStations(pageNo, pageSize);

        return ResponseEntity.status(HttpStatus.OK).body(ApiResult.successPage(response));
    }




}
