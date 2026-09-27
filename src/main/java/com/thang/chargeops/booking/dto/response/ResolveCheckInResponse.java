package com.thang.chargeops.booking.dto.response;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Builder
@Getter
@Setter
public class ResolveCheckInResponse {
    private UUID bookingId;
    private UUID connectorId;
    private String connectorCode;
    private Instant challengeExpiresAt;
    private Instant startAt;
    private Instant endAt;
    private Instant checkInDeadline;
    private Long expectedVersion;
}
