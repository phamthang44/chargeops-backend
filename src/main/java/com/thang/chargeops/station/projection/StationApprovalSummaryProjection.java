package com.thang.chargeops.station.projection;

import java.time.Instant;
import java.util.UUID;

public interface StationApprovalSummaryProjection {

    UUID getId();

    String getStationCode();

    String getName();

    String getOwnerDisplayName();

    String getProvinceName();

    int getPlannedChargePointCount();

    Instant getSubmittedAt();
}
