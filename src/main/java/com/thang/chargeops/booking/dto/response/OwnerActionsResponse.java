package com.thang.chargeops.booking.dto.response;

public record OwnerActionsResponse(
        boolean canCancelForStationFailure,
        boolean canViewFinancials,
        boolean canReportIncident
) {}
