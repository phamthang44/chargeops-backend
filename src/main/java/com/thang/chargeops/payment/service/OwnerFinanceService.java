package com.thang.chargeops.payment.service;

import com.thang.chargeops.payment.dto.response.OwnerFinanceBookingResponse;
import com.thang.chargeops.payment.dto.response.OwnerFinanceSummaryResponse;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface OwnerFinanceService {
    OwnerFinanceSummaryResponse summary();
    Page<OwnerFinanceBookingResponse> list(int pageNo, int pageSize);
    OwnerFinanceBookingResponse get(UUID bookingId);
}
