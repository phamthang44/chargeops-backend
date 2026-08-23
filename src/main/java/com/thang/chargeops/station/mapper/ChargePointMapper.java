package com.thang.chargeops.station.mapper;

import com.thang.chargeops.station.dto.chargepoint.response.ChargePointDetailResponse;
import com.thang.chargeops.station.dto.chargepoint.response.ConnectorDetailResponse;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import org.springframework.stereotype.Component;

/**
 * Explicit mapper kept intentionally small so the T17 response shape is easy to
 * inspect. Connector inventory is returned by its own endpoint, not nested into
 * every charge-point or station-detail response.
 */
@Component
public class ChargePointMapper {

    public ChargePointDetailResponse toResponse(ChargePoint chargePoint) {
        return new ChargePointDetailResponse(
                chargePoint.getId(),
                chargePoint.getStation().getId(),
                chargePoint.getChargePointCode(),
                chargePoint.getName(),
                chargePoint.getZoneLabel(),
                chargePoint.getMaxPowerKw(),
                chargePoint.getProvisioningStatus(),
                chargePoint.getOperationalChargePointStatus(),
                chargePoint.getCreatedAt()
        );
    }

    public ConnectorDetailResponse toResponse(Connector connector) {
        return new ConnectorDetailResponse(
                connector.getId(),
                connector.getChargePoint().getId(),
                connector.getConnectorCode(),
                connector.getConnectorType(),
                connector.getPowerKw(),
                connector.getChargerType(),
                connector.getRuntimeStatus(),
                connector.getCreatedAt()
        );
    }
}
