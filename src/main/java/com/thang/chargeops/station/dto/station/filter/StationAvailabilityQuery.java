package com.thang.chargeops.station.dto.station.filter;

import com.thang.chargeops.exception.errormessage.StationErrorMessage;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
public class StationAvailabilityQuery {
    @NotNull(message = StationErrorMessage.AVAILABILITY_CONNECTOR_REQUIRED_KEY)
    private UUID connectorId;

    @NotNull(message = StationErrorMessage.AVAILABILITY_DATE_REQUIRED_KEY)
    private LocalDate date;
}
