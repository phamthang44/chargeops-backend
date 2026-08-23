package com.thang.chargeops.station.policy.impl;

import com.thang.chargeops.common.enums.ProvisioningStatus;
import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.policy.ConnectorProvisioningPolicy;
import org.springframework.stereotype.Component;

@Component
public class ConnectorProvisioningPolicyImpl implements ConnectorProvisioningPolicy {

    @Override
    public void requireCanProvision(ChargePoint chargePoint) {
        requireDraftAtActiveStation(chargePoint);
    }

    @Override
    public void requireCanUpdate(ChargePoint chargePoint) {
        requireDraftAtActiveStation(chargePoint);
    }

    @Override
    public void requireCanDeleteDraft(ChargePoint chargePoint) {
        if (chargePoint.getProvisioningStatus() != ProvisioningStatus.PENDING_ACTIVATION) {
            throw new AppException(StationErrorCode.CONNECTOR_DRAFT_DELETE_ONLY);
        }
    }

    private void requireDraftAtActiveStation(ChargePoint chargePoint) {
        if (chargePoint.getProvisioningStatus() != ProvisioningStatus.PENDING_ACTIVATION) {
            throw new AppException(StationErrorCode.CONNECTOR_HARDWARE_LOCKED);
        }
        if (chargePoint.getStation().getStatus() != StationStatus.ACTIVE) {
            throw new AppException(
                    StationErrorCode.STATION_NOT_ELIGIBLE_FOR_PROVISIONING,
                    chargePoint.getStation().getStatus()
            );
        }
    }
}
