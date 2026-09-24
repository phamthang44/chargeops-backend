package com.thang.chargeops.refund.service;

import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.refund.model.CreateRefundObligationCommand;

/**
 * Called inside the cancellation/finding decision transaction. The caller owns the
 * Actor/Profile -> Connector -> Booking -> Payment locks, in that order. The service
 * locks the exact source receipt before reading/locking any Refund. No provider call.
 */
public interface RefundObligationService {
    Refund createObligation(CreateRefundObligationCommand command);
}
