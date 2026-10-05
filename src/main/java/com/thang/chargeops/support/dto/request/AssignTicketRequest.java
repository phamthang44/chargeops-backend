package com.thang.chargeops.support.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record AssignTicketRequest(
        @NotNull @Min(0) Long expectedVersion,
        @NotNull UUID handlerId,
        @NotBlank @Size(max = 2000) String reason
) {
}
