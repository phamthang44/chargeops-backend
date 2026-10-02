package com.thang.chargeops.support.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.support.dto.request.CreateTicketRequest;
import com.thang.chargeops.support.dto.request.MessageRequest;
import com.thang.chargeops.support.dto.request.ClaimTicketRequest;
import com.thang.chargeops.support.dto.request.AssignTicketRequest;
import com.thang.chargeops.support.dto.request.TicketStatusRequest;
import com.thang.chargeops.support.dto.response.TicketMessageResponse;
import com.thang.chargeops.support.dto.response.TicketResponse;
import com.thang.chargeops.support.model.TicketStatus;
import com.thang.chargeops.support.service.SupportTicketService;
import com.thang.chargeops.support.service.impl.TicketWorkflowService;
import com.thang.chargeops.support.service.TicketEventQueryService;
import com.thang.chargeops.support.dto.response.TicketEventResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "tickets")
@RequiredArgsConstructor
@Validated
public class SupportTicketController {
    private final SupportTicketService ticketService;
    private final TicketWorkflowService workflowService;
    private final TicketEventQueryService ticketEvents;

    @GetMapping("/{ticketId}/events")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResult<List<TicketEventResponse>>> events(@PathVariable UUID ticketId,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(ApiResult.successPage(ticketEvents.events(ticketId, page, size)));
    }

    @PostMapping("/{ticketId}/claim")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResult<TicketResponse>> claim(@PathVariable UUID ticketId, @Valid @RequestBody ClaimTicketRequest request) {
        return ResponseEntity.ok(ApiResult.success(workflowService.claim(ticketId, request.expectedVersion())));
    }

    @PostMapping("/{ticketId}/assignment")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResult<TicketResponse>> assign(@PathVariable UUID ticketId, @Valid @RequestBody AssignTicketRequest request) {
        return ResponseEntity.ok(ApiResult.success(workflowService.assign(ticketId, request)));
    }

    @PatchMapping("/{ticketId}/status")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResult<TicketResponse>> status(@PathVariable UUID ticketId, @Valid @RequestBody TicketStatusRequest request) {
        return ResponseEntity.ok(ApiResult.success(workflowService.changeStatus(ticketId, request)));
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResult<TicketResponse>> create(@Valid @RequestBody CreateTicketRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success(ticketService.create(request)));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResult<List<TicketResponse>>> getTickets(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) TicketStatus status,
            @RequestParam(required = false) UUID stationId
    ) {
        Page<TicketResponse> ticketPage = ticketService.getTickets(status, stationId, page, size);
        return ResponseEntity.ok(ApiResult.successPage(ticketPage));
    }

    @GetMapping("/{ticketId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResult<TicketResponse>> getTicket(@PathVariable UUID ticketId) {
        return ResponseEntity.ok(ApiResult.success(ticketService.getTicket(ticketId)));
    }

    @PostMapping("/{ticketId}/messages")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResult<TicketMessageResponse>> replyTicket(
            @PathVariable UUID ticketId,
            @RequestHeader(value = "Client-Message-Id", required = false) UUID clientMessageId,
            @Valid @RequestBody MessageRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResult.success(ticketService.replyTicket(ticketId, clientMessageId, request)));
    }
}
