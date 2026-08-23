package com.thang.chargeops.station.policy;

import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.station.entity.Connector;

/**
 * Owner-managed connector runtime rules.
 */
public interface ConnectorOperationPolicy {

    void requireCanChangeRuntimeStatus(
            Connector connector,
            RuntimeStatus targetStatus,
            String reason
    );
}
