package com.thang.chargeops.booking.support;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.repository.ChargePointRepository;
import com.thang.chargeops.station.repository.ConnectorRepository;
import com.thang.chargeops.station.repository.StationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class OwnerResourceScopeValidator {
    private final StationRepository stationRepository;
    private final ChargePointRepository chargePointRepository;
    private final ConnectorRepository connectorRepository;

    public void validate(UUID ownerId, UUID stationId, UUID chargePointId, UUID connectorId) {
        Station station = null;
        if (stationId != null) {
            station = stationRepository.findById(stationId)
                    .orElseThrow(() -> new AppException(StationErrorCode.STATION_NOT_FOUND, stationId));
            requireOwner(ownerId, station);
        }
        ChargePoint chargePoint = null;
        if (chargePointId != null) {
            chargePoint = chargePointRepository.findById(chargePointId)
                    .orElseThrow(() -> new AppException(StationErrorCode.CHARGE_POINT_NOT_FOUND, chargePointId));
            requireOwner(ownerId, chargePoint.getStation());
            if (station != null && !station.getId().equals(chargePoint.getStation().getId())) mismatch();
        }
        if (connectorId != null) {
            Connector connector = connectorRepository.findByIdWithChargePointAndStation(connectorId)
                    .orElseThrow(() -> new AppException(StationErrorCode.CONNECTOR_NOT_FOUND, connectorId));
            requireOwner(ownerId, connector.getChargePoint().getStation());
            if (station != null && !station.getId().equals(connector.getChargePoint().getStation().getId())) mismatch();
            if (chargePoint != null && !chargePoint.getId().equals(connector.getChargePoint().getId())) mismatch();
        }
    }

    private void requireOwner(UUID ownerId, Station station) {
        if (!station.getOwner().getId().equals(ownerId)) {
            throw new AppException(StationErrorCode.STATION_ACCESS_DENIED, station.getId());
        }
    }

    private void mismatch() {
        throw new AppException(CommonErrorCode.INVALID_REQUEST, "Owner booking topology filter mismatch");
    }
}
