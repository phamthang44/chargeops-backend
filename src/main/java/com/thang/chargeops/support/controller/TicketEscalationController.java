package com.thang.chargeops.support.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.support.dto.request.EscalateTicketRequest;
import com.thang.chargeops.support.dto.request.ReviewTicketEscalationRequest;
import com.thang.chargeops.support.dto.response.TicketEscalationResponse;
import com.thang.chargeops.support.dto.response.TicketEscalationsSummaryResponse;
import com.thang.chargeops.support.dto.response.TicketEscalationLookupResponse;
import com.thang.chargeops.support.service.TicketEscalationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Validated
public class TicketEscalationController {
    private final TicketEscalationService service;

    @GetMapping(SystemConstant.API_URL_PATTERN + "admin/ticket-escalations/summary")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResult<TicketEscalationsSummaryResponse>> adminSummary() {
        return ResponseEntity.ok(ApiResult.success(service.adminSummary()));
    }

    @PostMapping(SystemConstant.API_URL_PATTERN + "tickets/{ticketId}/escalation")
    @PreAuthorize("hasAnyRole('DRIVER', 'OWNER')")
    public ResponseEntity<ApiResult<TicketEscalationResponse>> request(
            @PathVariable UUID ticketId, @Valid @RequestBody EscalateTicketRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResult.success(service.request(ticketId, request)));
    }

    @PatchMapping(SystemConstant.API_URL_PATTERN + "admin/tickets/{ticketId}/escalation")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResult<TicketEscalationResponse>> review(
            @PathVariable UUID ticketId, @Valid @RequestBody ReviewTicketEscalationRequest request) {
        return ResponseEntity.ok(ApiResult.success(service.review(ticketId, request)));
    }

    @GetMapping(SystemConstant.API_URL_PATTERN + "tickets/{ticketId}/escalation")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<TicketEscalationLookupResponse> get(@PathVariable UUID ticketId) {
        return ResponseEntity.ok(TicketEscalationLookupResponse.of(service.getDetail(ticketId)));
    }

    @GetMapping(SystemConstant.API_URL_PATTERN + "admin/ticket-escalations")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResult<List<TicketEscalationResponse>>> adminQueue(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(ApiResult.successPage(service.adminQueue(page, size)));
    }
}
