package com.thang.chargeops.station.policy.impl;

import com.thang.chargeops.common.enums.ProvisioningStatus;
import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.policy.ChargePointProvisioningPolicy;
import org.springframework.stereotype.Component;

@Component
public class ChargePointProvisioningPolicyImpl implements ChargePointProvisioningPolicy {

    @Override
    public void requireCanProvision(Station station) {
        requireActiveStation(station);
    }

    @Override
    public void requireCanActivate(ChargePoint chargePoint, int expectedConnectorCount) {
        if (chargePoint.getProvisioningStatus() != ProvisioningStatus.PENDING_ACTIVATION) {
            throw invalidTransition(chargePoint, ProvisioningStatus.ACTIVE);
        }
        requireActiveStation(chargePoint.getStation());
        if (!chargePoint.hasConnectors()) {
            throw new AppException(StationErrorCode.CHARGE_POINT_REQUIRES_CONNECTOR);
        }

        int actualConnectorCount = chargePoint.getConnectors().size();
        if (expectedConnectorCount != actualConnectorCount) {
            throw new AppException(
                    StationErrorCode.CHARGE_POINT_CONNECTOR_COUNT_MISMATCH,
                    expectedConnectorCount,
                    actualConnectorCount
            );
        }
    }

    @Override
    public void requireCanSuspend(ChargePoint chargePoint, String reason) {
        if (chargePoint.getProvisioningStatus() != ProvisioningStatus.ACTIVE) {
            throw invalidTransition(chargePoint, ProvisioningStatus.SUSPENDED);
        }
        requireReason(reason);
    }

    @Override
    public void requireCanReactivate(ChargePoint chargePoint, String reason) {
        if (chargePoint.getProvisioningStatus() != ProvisioningStatus.SUSPENDED) {
            throw invalidTransition(chargePoint, ProvisioningStatus.ACTIVE);
        }
        requireActiveStation(chargePoint.getStation());
        requireReason(reason);
    }

    @Override
    public void requireCanDeleteDraft(ChargePoint chargePoint) {
        if (chargePoint.getProvisioningStatus() != ProvisioningStatus.PENDING_ACTIVATION) {
            throw new AppException(StationErrorCode.CHARGE_POINT_DRAFT_DELETE_ONLY);
        }
    }

    private void requireActiveStation(Station station) {
        if (station.getStatus() != StationStatus.ACTIVE) {
            throw new AppException(
                    StationErrorCode.STATION_NOT_ELIGIBLE_FOR_PROVISIONING,
                    station.getStatus()
            );
        }
    }

    private void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new AppException(StationErrorCode.CHARGE_POINT_STATUS_REASON_REQUIRED);
        }
    }

    private AppException invalidTransition(ChargePoint chargePoint, ProvisioningStatus target) {
        return new AppException(
                StationErrorCode.INVALID_CHARGE_POINT_PROVISIONING_TRANSITION,
                chargePoint.getProvisioningStatus(),
                target
        );
    }
}
