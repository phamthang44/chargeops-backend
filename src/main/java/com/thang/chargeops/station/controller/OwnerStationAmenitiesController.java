package com.thang.chargeops.station.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.station.dto.station.request.ReplaceStationAmenitiesRequest;
import com.thang.chargeops.station.dto.station.response.StationAmenitiesResponse;
import com.thang.chargeops.station.service.StationAmenitiesService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "owner/stations")
@RequiredArgsConstructor
@PreAuthorize("hasRole('OWNER')")
public class OwnerStationAmenitiesController {

    private final StationAmenitiesService stationAmenitiesService;

    @GetMapping("/{stationId}/amenities")
    public ResponseEntity<ApiResult<StationAmenitiesResponse>> getAmenities(
            @PathVariable UUID stationId
    ) {
        return ResponseEntity.ok(ApiResult.success(
                stationAmenitiesService.getOwnerAmenities(stationId)
        ));
    }

    @PutMapping("/{stationId}/amenities")
    public ResponseEntity<ApiResult<StationAmenitiesResponse>> replaceAmenities(
            @PathVariable UUID stationId,
            @Valid @RequestBody ReplaceStationAmenitiesRequest request
    ) {
        return ResponseEntity.ok(ApiResult.success(
                stationAmenitiesService.replaceOwnerAmenities(stationId, request)
        ));
    }
}
