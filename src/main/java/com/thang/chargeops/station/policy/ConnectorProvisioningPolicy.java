package com.thang.chargeops.station.policy;

import com.thang.chargeops.station.entity.ChargePoint;

/**
 * Admin-only connector topology rules while the parent is still a draft.
 */
public interface ConnectorProvisioningPolicy {

    void requireCanProvision(ChargePoint chargePoint);

    void requireCanUpdate(ChargePoint chargePoint);

    void requireCanDeleteDraft(ChargePoint chargePoint);
}
