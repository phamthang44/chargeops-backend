package com.thang.chargeops.station.dto.station.filter;

import com.thang.chargeops.common.enums.ChargerType;
import com.thang.chargeops.common.enums.ConnectorType;
import com.thang.chargeops.exception.errormessage.StationErrorMessage;
import com.thang.chargeops.station.dto.station.validation.ValidStationDiscoveryLocation;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Set;

@ValidStationDiscoveryLocation
@Getter
@Setter
public class StationDiscoveryFilter {
    @Size(max = 200, message = StationErrorMessage.DISCOVERY_QUERY_MAX_LENGTH_KEY)
    private String query;
    @Size(max = 4, message = StationErrorMessage.DISCOVERY_CONNECTOR_TYPES_MAX_KEY)
    private Set<ConnectorType> connectorTypes;
    private ChargerType chargerType;
    private Boolean availableOnly;
    private Boolean openOnly;

    @Size(max = 20, message = StationErrorMessage.DISCOVERY_PROVINCE_CODE_MAX_LENGTH_KEY)
    private String provinceCode;
    @DecimalMin(value = "0.0", message = StationErrorMessage.DISCOVERY_MIN_POWER_MIN_KEY)
    private BigDecimal minPowerKw;

    @DecimalMin(value = "-90.0", message = StationErrorMessage.STATION_LATITUDE_INVALID_KEY)
    @DecimalMax(value = "90.0", message = StationErrorMessage.STATION_LATITUDE_INVALID_KEY)
    private BigDecimal latitude;
    @DecimalMin(value = "-180.0", message = StationErrorMessage.STATION_LONGITUDE_INVALID_KEY)
    @DecimalMax(value = "180.0", message = StationErrorMessage.STATION_LONGITUDE_INVALID_KEY)
    private BigDecimal longitude;
    @DecimalMin(
            value = "0.0",
            inclusive = false,
            message = StationErrorMessage.DISCOVERY_MAX_DISTANCE_MIN_KEY
    )
    private BigDecimal maxDistanceKm;

    private StationDiscoverySort sort = StationDiscoverySort.NEAREST;

}
