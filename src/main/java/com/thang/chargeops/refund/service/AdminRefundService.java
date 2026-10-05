package com.thang.chargeops.refund.service;

import com.thang.chargeops.refund.dto.request.ExecuteRefundRequest;
import com.thang.chargeops.refund.dto.response.RefundDetailResponse;
import com.thang.chargeops.refund.model.RefundStatus;
import org.springframework.data.domain.Page;

import java.util.Map;
import java.util.UUID;

public interface AdminRefundService {
    Page<RefundDetailResponse> list(RefundStatus status, String search, int pageNo, int pageSize);
    Map<String, Long> counts();
    RefundDetailResponse get(UUID refundId);
    RefundDetailResponse execute(UUID refundId, UUID requestKey, ExecuteRefundRequest request);
}

