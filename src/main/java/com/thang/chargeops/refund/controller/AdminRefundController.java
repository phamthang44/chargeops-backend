package com.thang.chargeops.refund.controller;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.refund.dto.request.ExecuteRefundRequest;
import com.thang.chargeops.refund.dto.response.RefundDetailResponse;
import com.thang.chargeops.refund.model.RefundStatus;
import com.thang.chargeops.refund.service.AdminRefundService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(SystemConstant.API_URL_PATTERN + "admin/refunds")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminRefundController {
    private final AdminRefundService adminRefundService;

    @GetMapping
    public ResponseEntity<ApiResult<List<RefundDetailResponse>>> list(
            @RequestParam(required = false) RefundStatus status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        Page<RefundDetailResponse> result = adminRefundService.list(status, search, page, size);
        return ResponseEntity.ok(ApiResult.successPage(result, adminRefundService.counts()));
    }

    @GetMapping("/{refundId}")
    public ResponseEntity<ApiResult<RefundDetailResponse>> get(@PathVariable UUID refundId) {
        return ResponseEntity.ok(ApiResult.success(adminRefundService.get(refundId)));
    }

    @PostMapping("/{refundId}/execute")
    public ResponseEntity<ApiResult<RefundDetailResponse>> execute(
            @PathVariable UUID refundId,
            @RequestHeader("Idempotency-Key") UUID requestKey,
            @Valid @RequestBody ExecuteRefundRequest request
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResult.success(adminRefundService.execute(refundId, requestKey, request)));
    }
}

