package com.thang.chargeops.station.dto.station.request;

import jakarta.validation.constraints.Size;

public record StationStatusChangeRequest(
        @Size(max = 500, message = "Reason cannot exceed 500 characters")
        String reason
) {
}
