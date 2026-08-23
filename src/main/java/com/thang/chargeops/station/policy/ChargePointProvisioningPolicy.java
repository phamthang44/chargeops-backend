package com.thang.chargeops.station.policy;

import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Station;

/**
 * Administrative topology and provisioning rules for a charge point.
 */
public interface ChargePointProvisioningPolicy {

    void requireCanProvision(Station station);

    void requireCanActivate(ChargePoint chargePoint, int expectedConnectorCount);

    void requireCanSuspend(ChargePoint chargePoint, String reason);

    void requireCanReactivate(ChargePoint chargePoint, String reason);

    void requireCanDeleteDraft(ChargePoint chargePoint);
}
