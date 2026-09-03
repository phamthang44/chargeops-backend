package com.thang.chargeops.station.policy;

import com.thang.chargeops.station.entity.Station;

import java.time.Instant;

/** Determines whether a station may be exposed in public driver surfaces. */
public interface StationVisibilityPolicy {

    boolean isVisibleToDrivers(Station station, Instant at);
}
