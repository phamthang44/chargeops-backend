package com.thang.chargeops.refund.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record OwnerRefundRetryRequest(@NotNull @PositiveOrZero Long expectedVersion) {
    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("Unsupported refund retry field: " + field);
    }
}
