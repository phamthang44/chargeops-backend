package com.thang.chargeops.station.staff.dto;

import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record StaffChargePointStatusRequest(
        @NotNull OperationalChargePointStatus operationalStatus,
        @NotNull @PositiveOrZero Long expectedVersion,
        @Size(max = 500) String reason) { }
