package com.thang.chargeops.support.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.support.dto.response.AdminOperationsSummaryResponse;
import com.thang.chargeops.support.dto.response.AdminTicketSummaryResponse;
import com.thang.chargeops.support.service.AdminOperationsSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminOperationsSummaryController {
    private final AdminOperationsSummaryService service;

    @GetMapping("/dashboard/summary")
    public ResponseEntity<ApiResult<AdminOperationsSummaryResponse>> dashboard() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.success(service.dashboard()));
    }

    @GetMapping("/tickets/summary")
    public ResponseEntity<ApiResult<AdminTicketSummaryResponse>> platformTickets() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.success(service.tickets(false)));
    }

    @GetMapping("/tickets/escalated/summary")
    public ResponseEntity<ApiResult<AdminTicketSummaryResponse>> escalatedTickets() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.success(service.tickets(true)));
    }
}
