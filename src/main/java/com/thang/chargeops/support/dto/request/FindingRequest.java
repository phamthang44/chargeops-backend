package com.thang.chargeops.support.dto.request;

import com.thang.chargeops.support.model.TicketFindingConclusion;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record FindingRequest(
        @NotNull @Min(0) Long expectedVersion,
        @NotNull TicketFindingConclusion conclusion,
        @NotNull Instant affectedAt,
        @NotBlank @Size(max = 2000) String reason
) {
}
