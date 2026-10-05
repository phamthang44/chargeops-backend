package com.thang.chargeops.support.dto.request;

import com.thang.chargeops.support.model.TicketEscalationClosureReason;
import com.thang.chargeops.support.model.TicketEscalationResolutionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewTicketEscalationRequest(
        @NotNull Long expectedVersion,
        @NotNull TicketEscalationResolutionType action,
        TicketEscalationClosureReason closureReason,
        @NotBlank @Size(max = 2000) String note
) {}
