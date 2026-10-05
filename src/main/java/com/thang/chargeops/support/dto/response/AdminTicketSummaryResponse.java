package com.thang.chargeops.support.dto.response;

import java.util.Map;

public record AdminTicketSummaryResponse(long total, Map<String, Long> byStatus,
                                         long open, long inProgress, long resolved, long closed) {}
