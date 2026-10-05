package com.thang.chargeops.support.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.thang.chargeops.common.response.ApiResult;

public record TicketEscalationLookupResponse(
        @JsonInclude(JsonInclude.Include.ALWAYS) TicketEscalationDetailResponse data,
        ApiResult.Meta meta
) {
    public static TicketEscalationLookupResponse of(TicketEscalationDetailResponse escalation) {
        return new TicketEscalationLookupResponse(escalation, ApiResult.Meta.builder().build());
    }

    public static TicketEscalationLookupResponse of(TicketEscalationResponse escalation) {
        return new TicketEscalationLookupResponse(
                escalation == null ? null : new TicketEscalationDetailResponse(
                        escalation.ticketId(), escalation.requestedBy(), escalation.requestedAt(), escalation.reason(), null),
                ApiResult.Meta.builder().build());
    }
}
