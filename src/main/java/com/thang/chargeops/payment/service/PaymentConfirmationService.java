package com.thang.chargeops.payment.service;

import com.thang.chargeops.payment.model.NormalizedReceipt;
import com.thang.chargeops.payment.model.PaymentReceiptResult;

public interface PaymentConfirmationService {

    /**
     * Process an incoming verified payment receipt atomically.
     * Enforces deduplication, deadlock prevention lock order (Connector -> Booking -> Payment),
     * clock evaluation after lock, hold deadline validation, exact-order matching,
     * and atomic state transition for Booking and Payment.
     *
     * @param receipt normalized receipt from gateway or webhook adapter
     * @return outcome result (APPLIED, DUPLICATE, UNAPPLIED, UNMATCHED)
     */
    PaymentReceiptResult processReceipt(NormalizedReceipt receipt);
}
