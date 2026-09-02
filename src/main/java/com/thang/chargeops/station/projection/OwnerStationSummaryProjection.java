package com.thang.chargeops.station.projection;

import com.thang.chargeops.common.enums.Plan;
import com.thang.chargeops.common.enums.StationStatus;

import java.time.Instant;
import java.util.UUID;

public interface OwnerStationSummaryProjection {

    UUID getId();

    String getStationCode();

    String getName();

    String getAddressLine();

    String getProvinceName();

    String getWardName();

    int getPlannedChargePointCount();

    StationStatus getStatus();

    Plan getLicensePlan();

    Instant getLicenseExpiresAt();

    int getOnlineChargePointCount();

    int getActualChargePointCount();
}
