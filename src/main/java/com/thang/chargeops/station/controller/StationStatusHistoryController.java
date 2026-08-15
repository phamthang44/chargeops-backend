package com.thang.chargeops.station.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.station.dto.station.response.StationStatusHistoryResponse;
import com.thang.chargeops.station.service.StationStatusHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "stations")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
public class StationStatusHistoryController {

    private final StationStatusHistoryService stationStatusHistoryService;

    @GetMapping("/{stationId}/status-history")
    public ResponseEntity<ApiResult<List<StationStatusHistoryResponse>>> getStationHistory(
            @PathVariable UUID stationId
    ) {
        return ResponseEntity.ok(ApiResult.success(stationStatusHistoryService.getHistory(stationId)));
    }
}
