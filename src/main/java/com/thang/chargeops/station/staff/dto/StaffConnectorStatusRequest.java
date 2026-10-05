package com.thang.chargeops.station.staff.dto;

import com.thang.chargeops.common.enums.RuntimeStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record StaffConnectorStatusRequest(
        @NotNull RuntimeStatus runtimeStatus,
        @NotNull @PositiveOrZero Long expectedVersion,
        @Size(max = 500) String reason) { }
