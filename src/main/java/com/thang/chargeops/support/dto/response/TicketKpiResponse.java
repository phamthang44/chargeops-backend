package com.thang.chargeops.support.dto.response;

import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonProperty;

public record TicketKpiResponse(UUID stationId, UUID staffId, Instant from, Instant to,
                                long selfClaimedTickets, long assignedTickets,
                                long resolvedTickets, long completedTickets,
                                long reporterConfirmedCompletedTickets,
                                long autoClosedCompletedTickets) {
    @JsonProperty("periodFrom")
    public Instant periodFrom() { return from; }

    @JsonProperty("periodTo")
    public Instant periodTo() { return to; }
}
