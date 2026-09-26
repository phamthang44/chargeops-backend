package com.thang.chargeops.refund.service;

import java.util.UUID;

public interface AutomaticRefundExecutionService {
    boolean processFirstAttempt(UUID refundId);
}
