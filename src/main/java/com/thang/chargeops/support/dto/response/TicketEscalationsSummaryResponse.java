package com.thang.chargeops.support.dto.response;

public record TicketEscalationsSummaryResponse(long totalEscalated, long pendingArbiter,
                                               long unresponsive24hCount, long disputedFindingCount) {}
