package com.thang.chargeops.station.service.model;

import java.time.Instant;

public record OperatingWindow(
        Instant startAt,
        Instant endAt
) {
}
