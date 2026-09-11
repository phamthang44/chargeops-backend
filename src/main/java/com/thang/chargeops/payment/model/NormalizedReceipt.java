package com.thang.chargeops.payment.model;

import com.thang.chargeops.common.enums.PaymentApplicationClassification;

import java.math.BigDecimal;
import java.time.Instant;

public record NormalizedReceipt(
        String provider,
        String receivingAccountRef,
        String transactionRef,
        BigDecimal amount,
        String currency,
        Instant providerPaidAt,
        Instant receivedAt,
        PaymentApplicationClassification classification,
        String paymentCode,
        String transferContent,
        String rawPayload
) {
}
