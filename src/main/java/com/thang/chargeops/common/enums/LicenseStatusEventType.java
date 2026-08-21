package com.thang.chargeops.common.enums;

import java.util.Set;

public enum LicenseStatusEventType {
    ISSUED(Set.of(), LicenseStatus.PENDING),
    ACTIVATED(Set.of(LicenseStatus.PENDING), LicenseStatus.ACTIVE),
    SUSPENDED(Set.of(LicenseStatus.ACTIVE), LicenseStatus.SUSPENDED),
    REACTIVATED(Set.of(LicenseStatus.SUSPENDED), LicenseStatus.ACTIVE),
    CANCELLED(
            Set.of(LicenseStatus.PENDING, LicenseStatus.ACTIVE, LicenseStatus.SUSPENDED),
            LicenseStatus.CANCELLED
    ),
    EXPIRED(
            Set.of(LicenseStatus.ACTIVE, LicenseStatus.SUSPENDED),
            LicenseStatus.EXPIRED
    );

    private final Set<LicenseStatus> allowedFromStatuses;
    private final LicenseStatus toStatus;

    LicenseStatusEventType(Set<LicenseStatus> allowedFromStatuses, LicenseStatus toStatus) {
        this.allowedFromStatuses = allowedFromStatuses;
        this.toStatus = toStatus;
    }

    public LicenseStatus getToStatus() {
        return toStatus;
    }

    public boolean supports(LicenseStatus fromStatus, LicenseStatus toStatus) {
        if (this == ISSUED) {
            return fromStatus == null && toStatus == this.toStatus;
        }

        return allowedFromStatuses.contains(fromStatus) && toStatus == this.toStatus;
    }
}
