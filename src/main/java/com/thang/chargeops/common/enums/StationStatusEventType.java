package com.thang.chargeops.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum StationStatusEventType {
    SUBMITTED(null, StationStatus.PENDING_APPROVAL, false),
    APPROVED(StationStatus.PENDING_APPROVAL, StationStatus.ACTIVE, false),
    REJECTED(StationStatus.PENDING_APPROVAL, StationStatus.REJECTED, true),
    RESUBMITTED(StationStatus.REJECTED, StationStatus.PENDING_APPROVAL, false),
    SUSPENDED(StationStatus.ACTIVE, StationStatus.SUSPENDED, true),
    REACTIVATED(StationStatus.SUSPENDED, StationStatus.ACTIVE, false),
    WITHDRAWN(StationStatus.PENDING_APPROVAL, StationStatus.WITHDRAWN, false);

    private final StationStatus fromStatus;
    private final StationStatus toStatus;
    private final boolean reasonRequired;
}
