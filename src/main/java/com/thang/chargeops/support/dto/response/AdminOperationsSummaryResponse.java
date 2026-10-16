package com.thang.chargeops.support.dto.response;

import java.time.Instant;

public record AdminOperationsSummaryResponse(long activeStations, long pendingApprovals,
                                             long platformOpenTickets, long escalatedOpenCases,
                                             Instant generatedAt) {}
