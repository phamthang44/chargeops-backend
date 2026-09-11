package com.thang.chargeops.payment.dto.request;

public record SepayWebhookRequest(
        String gateway,
        String transactionDate,
        String accountNumber,
        String subAccount,
        String code,
        String content,
        String transferType,
        String description,
        Long transferAmount,
        String referenceCode,
        Long accumulated,
        Long id
) {
}
