package com.thang.chargeops.station.controller;

import com.thang.chargeops.booking.service.StationAvailabilityService;
import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.exception.errormessage.StationErrorMessage;
import com.thang.chargeops.station.dto.station.filter.StationAvailabilityQuery;
import com.thang.chargeops.station.dto.station.filter.StationDiscoveryFilter;
import com.thang.chargeops.station.dto.station.response.StationAvailabilityResponse;
import com.thang.chargeops.station.dto.station.response.StationDiscoveryDetailResponse;
import com.thang.chargeops.station.dto.station.response.StationDiscoveryItemResponse;
import com.thang.chargeops.station.service.StationDetailService;
import com.thang.chargeops.station.service.StationDiscoveryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "stations")
@RequiredArgsConstructor
public class StationDiscoveryController {

    private final StationDiscoveryService stationDiscoveryService;
    private final StationDetailService stationDetailService;
    private final StationAvailabilityService stationAvailabilityService;

    @GetMapping
    public ResponseEntity<ApiResult<List<StationDiscoveryItemResponse>>> getStations(
            @Valid @ModelAttribute StationDiscoveryFilter filter,
            @RequestParam(defaultValue = "1")
            @Min(value = 1, message = StationErrorMessage.PAGE_NUMBER_MIN_KEY) int page,
            @RequestParam(defaultValue = "12")
            @Min(value = 1, message = StationErrorMessage.PAGE_SIZE_MIN_KEY)
            @Max(value = 100, message = StationErrorMessage.PAGE_SIZE_MAX_KEY) int size
    ) {
        return ResponseEntity.ok(ApiResult.successPage(
                stationDiscoveryService.searchStations(filter, page, size)
        ));
    }

    @GetMapping("/{stationId}")
    public ResponseEntity<ApiResult<StationDiscoveryDetailResponse>> getDetailStation(
            @PathVariable UUID stationId
    ) {
        return ResponseEntity.ok(ApiResult.success(stationDetailService.getStationDetail(stationId)));
    }

    @GetMapping("/{stationId}/availability")
    public ResponseEntity<ApiResult<StationAvailabilityResponse>> getStationDetailAvailability(
            @PathVariable UUID stationId,
        @Valid @ModelAttribute StationAvailabilityQuery query
    ) {
        return ResponseEntity.ok(ApiResult.success(
                stationAvailabilityService.getAvailability(stationId, query)
        ));
    }

}
