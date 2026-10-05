package com.thang.chargeops.payment.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SepayWebhookRequest(
        String gateway,
        @JsonProperty("transactionDate") String transactionDate,
        @JsonProperty("accountNumber") String accountNumber,
        @JsonProperty("subAccount") String subAccount,
        String code,
        String content,
        @JsonProperty("transferType") String transferType,
        String description,
        @JsonProperty("transferAmount") Long transferAmount,
        @JsonProperty("referenceCode") String referenceCode,
        Long accumulated,
        Long id
) {
}
