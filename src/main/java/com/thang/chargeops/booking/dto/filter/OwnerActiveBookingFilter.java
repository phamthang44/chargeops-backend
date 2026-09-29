package com.thang.chargeops.booking.dto.filter;

import java.util.UUID;

public record OwnerActiveBookingFilter(UUID stationId, UUID chargePointId, UUID connectorId) {}
