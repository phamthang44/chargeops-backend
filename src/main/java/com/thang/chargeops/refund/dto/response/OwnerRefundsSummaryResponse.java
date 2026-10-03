package com.thang.chargeops.refund.dto.response;

public record OwnerRefundsSummaryResponse(long totalPendingCount, long totalSucceededCount,
                                          long totalFailedAttemptsCount, long requiresOwnerActionCount,
                                          long totalRefundAmountVnd, long pendingRefundAmountVnd) {}
