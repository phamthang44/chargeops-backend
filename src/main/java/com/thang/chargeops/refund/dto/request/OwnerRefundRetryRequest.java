package com.thang.chargeops.refund.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record OwnerRefundRetryRequest(@NotNull @PositiveOrZero Long expectedVersion) {
}
