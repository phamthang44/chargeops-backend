package com.thang.chargeops.support.dto.response;

public record AdminOperationsSummaryResponse(long activeStations, long pendingApprovals,
                                             long platformOpenTickets, long escalatedOpenCases) {}
