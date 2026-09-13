package com.thang.chargeops.station.policy.impl;

import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import com.thang.chargeops.common.enums.ProvisioningStatus;
import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.policy.ConnectorBookabilityPolicy;
import com.thang.chargeops.station.policy.StationBusinessEligibilityPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class ConnectorBookabilityPolicyImpl implements ConnectorBookabilityPolicy {

    private final StationBusinessEligibilityPolicy stationBusinessEligibilityPolicy;

    @Override
    public void requireBookableForNewBooking(Connector connector, Instant at) {
        ChargePoint chargePoint = connector.getChargePoint();
        stationBusinessEligibilityPolicy.requireEligibleForNewBusiness(
                chargePoint.getStation(),
                at
        );

        boolean bookable = chargePoint.getProvisioningStatus() == ProvisioningStatus.ACTIVE
                && chargePoint.getOperationalChargePointStatus()
                == OperationalChargePointStatus.AVAILABLE
                && (connector.getRuntimeStatus() == RuntimeStatus.AVAILABLE || connector.getRuntimeStatus() == RuntimeStatus.IN_USE);
        if (!bookable) {
            throw new AppException(
                    StationErrorCode.CONNECTOR_NOT_BOOKABLE,
                    connector.getConnectorCode()
            );
        }
    }
}
