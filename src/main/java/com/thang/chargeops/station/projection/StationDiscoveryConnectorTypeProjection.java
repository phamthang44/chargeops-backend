package com.thang.chargeops.station.projection;

import com.thang.chargeops.common.enums.ConnectorType;

import java.util.UUID;

/**
 * One distinct connector type offered by one station in a discovery result page.
 * The service groups these rows by station id before building the response DTOs.
 */
public interface StationDiscoveryConnectorTypeProjection {

    UUID getStationId();

    ConnectorType getConnectorType();
}
