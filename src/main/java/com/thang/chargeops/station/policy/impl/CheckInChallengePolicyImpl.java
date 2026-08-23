package com.thang.chargeops.station.policy.impl;

import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import com.thang.chargeops.common.enums.ProvisioningStatus;
import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.policy.CheckInChallengePolicy;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class CheckInChallengePolicyImpl implements CheckInChallengePolicy {

    @Override
    public void requireCanIssue(Connector connector, Instant at) {
        ChargePoint chargePoint = connector.getChargePoint();
        Station station = chargePoint.getStation();

        // License is intentionally not checked here. Entitlement belongs to
        // new-booking creation, not to a short-lived challenge for an existing booking.
        boolean eligible = station.getStatus() == StationStatus.ACTIVE
                && chargePoint.getProvisioningStatus() == ProvisioningStatus.ACTIVE
                && chargePoint.getOperationalChargePointStatus()
                == OperationalChargePointStatus.AVAILABLE
                && connector.getRuntimeStatus() == RuntimeStatus.AVAILABLE;

        if (!eligible) {
            throw new AppException(
                    StationErrorCode.CONNECTOR_NOT_AVAILABLE_FOR_CHECK_IN,
                    connector.getConnectorCode()
            );
        }
    }
}
