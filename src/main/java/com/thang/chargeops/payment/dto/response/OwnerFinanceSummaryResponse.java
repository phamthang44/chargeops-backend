package com.thang.chargeops.payment.dto.response;

public record OwnerFinanceSummaryResponse(long grossVnd, long refundedVnd, long netVnd,
                                          long pendingRefundVnd, long totalBookings,
                                          long paidBookings) {}
