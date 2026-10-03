package com.thang.chargeops.refund.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.refund.dto.request.OwnerRefundRetryRequest;
import com.thang.chargeops.refund.dto.response.OwnerRefundResponse;
import com.thang.chargeops.refund.dto.response.OwnerRefundsSummaryResponse;
import com.thang.chargeops.refund.model.RefundStatus;
import com.thang.chargeops.refund.service.OwnerRefundService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@Profile({"dev", "demo", "test"})
@RequestMapping(SystemConstant.API_URL_PATTERN + "owner/refunds")
@PreAuthorize("hasRole('OWNER')")
@RequiredArgsConstructor
@Validated
public class OwnerRefundController {
    private final OwnerRefundService service;

    @GetMapping("/summary")
    public ResponseEntity<ApiResult<OwnerRefundsSummaryResponse>> summary() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.success(service.summary()));
    }

    @GetMapping
    public ResponseEntity<ApiResult<List<OwnerRefundResponse>>> list(
            @RequestParam(required = false) RefundStatus status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.successPage(service.list(status, page, size)));
    }

    @GetMapping("/{refundId}")
    public ResponseEntity<ApiResult<OwnerRefundResponse>> get(@PathVariable UUID refundId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.success(service.get(refundId)));
    }

    @PostMapping("/{refundId}/retry")
    public ResponseEntity<ApiResult<OwnerRefundResponse>> retry(
            @PathVariable UUID refundId,
            @RequestHeader("Idempotency-Key") UUID requestKey,
            @Valid @RequestBody OwnerRefundRetryRequest request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResult.success(service.retry(refundId, requestKey, request.expectedVersion())));
    }
}
