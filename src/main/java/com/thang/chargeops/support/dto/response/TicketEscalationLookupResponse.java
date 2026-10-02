package com.thang.chargeops.support.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.thang.chargeops.common.response.ApiResult;

public record TicketEscalationLookupResponse(
        @JsonInclude(JsonInclude.Include.ALWAYS) TicketEscalationResponse data,
        ApiResult.Meta meta
) {
    public static TicketEscalationLookupResponse of(TicketEscalationResponse escalation) {
        return new TicketEscalationLookupResponse(escalation, ApiResult.Meta.builder().build());
    }
}
