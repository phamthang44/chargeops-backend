package com.thang.chargeops.support.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.support.dto.response.TicketKpiResponse;
import com.thang.chargeops.support.service.TicketKpiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "stations/{stationId}/ticket-kpis")
@RequiredArgsConstructor
public class TicketKpiController {
    private final TicketKpiService service;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResult<TicketKpiResponse>> get(@PathVariable UUID stationId,
        @RequestParam Instant from, @RequestParam Instant to, @RequestParam(required = false) UUID staffId) {
        return ResponseEntity.ok(ApiResult.success(service.get(stationId, staffId, from, to)));
    }
}
