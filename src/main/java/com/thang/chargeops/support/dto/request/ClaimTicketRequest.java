package com.thang.chargeops.support.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ClaimTicketRequest(@NotNull @Min(0) Long expectedVersion) {}
