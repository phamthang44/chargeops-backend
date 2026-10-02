package com.thang.chargeops.payment.service;

import com.thang.chargeops.payment.dto.response.OwnerFinanceBookingResponse;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface OwnerFinanceService {
    Page<OwnerFinanceBookingResponse> list(int pageNo, int pageSize);
    OwnerFinanceBookingResponse get(UUID bookingId);
}
