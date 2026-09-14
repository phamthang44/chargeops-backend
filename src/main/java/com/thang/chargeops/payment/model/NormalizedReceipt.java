package com.thang.chargeops.payment.model;


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
        String vaNumber,
        String paymentCode,
        String transferContent,
        String rawPayload
) {
}
