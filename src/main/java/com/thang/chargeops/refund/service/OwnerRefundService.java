package com.thang.chargeops.refund.service;

import com.thang.chargeops.refund.dto.response.OwnerRefundResponse;
import com.thang.chargeops.refund.dto.response.OwnerRefundsSummaryResponse;
import com.thang.chargeops.refund.model.RefundStatus;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface OwnerRefundService {
    OwnerRefundsSummaryResponse summary();
    Page<OwnerRefundResponse> list(RefundStatus status, int pageNo, int pageSize);
    OwnerRefundResponse get(UUID refundId);
    OwnerRefundResponse retry(UUID refundId, UUID requestKey, long expectedVersion);
}
