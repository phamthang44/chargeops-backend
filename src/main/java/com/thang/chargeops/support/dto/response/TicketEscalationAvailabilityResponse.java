package com.thang.chargeops.support.dto.response;

import java.time.Instant;

public record TicketEscalationAvailabilityResponse(
        boolean canRequest,
        Instant availableAt,
        Reason reason
) {
    public enum Reason {
        ALREADY_ESCALATED,
        PLATFORM_TICKET,
        TICKET_CLOSED,
        TICKET_AWAITING_REPORTER,
        NOT_REQUESTER,
        OWNER_CAN_REQUEST,
        STATION_DENIED,
        REPORTER_CONTINUED,
        WAITING_FOR_STATION,
        STATION_UNRESPONSIVE,
        STATION_RESPONDED,
        NO_REPORTER_MESSAGE
    }
}
