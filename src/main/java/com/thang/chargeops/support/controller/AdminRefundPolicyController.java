package com.thang.chargeops.support.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.booking.dto.response.ServiceFailureCommandResponse;
import com.thang.chargeops.support.dto.request.ReviewRefundPolicyRequest;
import com.thang.chargeops.support.dto.response.RefundPolicyContextResponse;
import com.thang.chargeops.support.service.AdminRefundPolicyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@RequestMapping(SystemConstant.API_URL_PATTERN + "admin/tickets/{ticketId}/escalations/{escalationId}")
public class AdminRefundPolicyController {
    private final AdminRefundPolicyService service;

    @PostMapping("/refund-policy-decisions")
    public ResponseEntity<ApiResult<ServiceFailureCommandResponse>> review(
            @PathVariable UUID ticketId, @PathVariable UUID escalationId,
            @RequestHeader("Idempotency-Key") UUID key, @Valid @RequestBody ReviewRefundPolicyRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.success(service.review(ticketId, escalationId, key, request)));
    }

    @GetMapping("/refund-policy-context")
    public ResponseEntity<ApiResult<RefundPolicyContextResponse>> context(
            @PathVariable UUID ticketId, @PathVariable UUID escalationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.success(service.context(ticketId, escalationId)));
    }
}
