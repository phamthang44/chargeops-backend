package com.thang.chargeops.support.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.support.dto.request.AssignTicketRequest;
import com.thang.chargeops.support.dto.request.ClaimTicketRequest;
import com.thang.chargeops.support.dto.request.MessageRequest;
import com.thang.chargeops.support.dto.request.TicketStatusRequest;
import com.thang.chargeops.support.dto.response.TicketEventResponse;
import com.thang.chargeops.support.dto.response.TicketMessageResponse;
import com.thang.chargeops.support.dto.response.TicketResponse;
import com.thang.chargeops.support.dto.response.TicketDetailResponse;
import com.thang.chargeops.support.model.TicketStatus;
import com.thang.chargeops.support.service.AdminTicketService;
import com.thang.chargeops.support.service.TicketEventQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "admin/tickets")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Validated
public class AdminTicketController {
    private final AdminTicketService service;
    private final TicketEventQueryService events;

    @GetMapping
    public ResponseEntity<ApiResult<List<TicketResponse>>> platformQueue(
            @RequestParam(required = false) TicketStatus status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(ApiResult.successPage(service.platformQueue(status, page, size)));
    }

    @GetMapping("/escalated")
    public ResponseEntity<ApiResult<List<TicketResponse>>> escalated(
            @RequestParam(required = false) UUID stationId,
            @RequestParam(required = false) TicketStatus status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(ApiResult.successPage(service.stationAudit(stationId, status, page, size)));
    }

    @GetMapping("/{ticketId}")
    public ResponseEntity<ApiResult<TicketDetailResponse>> get(@PathVariable UUID ticketId) {
        return ResponseEntity.ok(ApiResult.success(service.getDetail(ticketId)));
    }

    @GetMapping("/{ticketId}/messages")
    public ResponseEntity<ApiResult<List<TicketMessageResponse>>> messages(@PathVariable UUID ticketId) {
        return ResponseEntity.ok(ApiResult.success(service.get(ticketId).messages()));
    }

    @GetMapping("/{ticketId}/events")
    public ResponseEntity<ApiResult<List<TicketEventResponse>>> events(@PathVariable UUID ticketId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(ApiResult.successPage(events.events(ticketId, page, size)));
    }

    @PostMapping("/{ticketId}/claim")
    public ResponseEntity<ApiResult<TicketResponse>> claim(@PathVariable UUID ticketId,
            @Valid @RequestBody ClaimTicketRequest request) {
        return ResponseEntity.ok(ApiResult.success(service.claim(ticketId, request.expectedVersion())));
    }

    @PostMapping("/{ticketId}/assignment")
    public ResponseEntity<ApiResult<TicketResponse>> assign(@PathVariable UUID ticketId,
            @Valid @RequestBody AssignTicketRequest request) {
        return ResponseEntity.ok(ApiResult.success(service.assign(ticketId, request)));
    }

    @PatchMapping("/{ticketId}/status")
    public ResponseEntity<ApiResult<TicketResponse>> changeStatus(@PathVariable UUID ticketId,
            @Valid @RequestBody TicketStatusRequest request) {
        return ResponseEntity.ok(ApiResult.success(service.changeStatus(ticketId, request)));
    }

    @PostMapping("/{ticketId}/messages")
    public ResponseEntity<ApiResult<TicketMessageResponse>> reply(@PathVariable UUID ticketId,
            @RequestHeader(value = "Client-Message-Id", required = false) UUID clientMessageId,
            @Valid @RequestBody MessageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success(service.reply(ticketId, clientMessageId, request)));
    }
}
