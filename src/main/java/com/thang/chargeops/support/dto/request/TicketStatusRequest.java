package com.thang.chargeops.support.dto.request;

import com.thang.chargeops.support.model.TicketStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TicketStatusRequest(
        @NotNull @Min(0) Long expectedVersion,
        @NotNull TicketStatus status,
        @Size(max = 2000) String reason
) {
}
