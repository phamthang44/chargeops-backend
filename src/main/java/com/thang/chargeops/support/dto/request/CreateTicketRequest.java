package com.thang.chargeops.support.dto.request;

import com.thang.chargeops.support.model.TicketCategory;
import com.thang.chargeops.support.model.TicketPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateTicketRequest(
        @NotNull TicketCategory category,
        @NotNull TicketPriority priority,
        @NotBlank @Size(max = 160) String subject,
        @NotBlank @Size(max = 2000) String description,
        UUID bookingId,
        UUID stationId
) {
}
