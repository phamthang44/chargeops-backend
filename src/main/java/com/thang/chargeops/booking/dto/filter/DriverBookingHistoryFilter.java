package com.thang.chargeops.booking.dto.filter;

public record DriverBookingHistoryFilter(
        String query,
        HistoryStatus status
) {
    public DriverBookingHistoryFilter {
        query = query == null ? "" : query.trim();
        status = status == null ? HistoryStatus.ALL : status;
    }

    public DriverBookingHistoryFilter withStatus(HistoryStatus nextStatus) {
        return new DriverBookingHistoryFilter(query, nextStatus);
    }

    public enum HistoryStatus {
        ALL,
        COMPLETED,
        CANCELLED
    }
}
