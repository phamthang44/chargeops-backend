package com.thang.chargeops.station.policy;

import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import com.thang.chargeops.station.entity.ChargePoint;

/**
 * Owner-managed runtime rules. Admin suspension still overrides this state.
 */
public interface ChargePointOperationPolicy {

    void requireCanChangeOperationalStatus(
            ChargePoint chargePoint,
            OperationalChargePointStatus targetStatus,
            String reason
    );
}
