package com.thang.chargeops.station.policy;

import com.thang.chargeops.station.entity.License;

import java.time.Instant;

/**
 * Cross-domain extension points for License lifecycle commands.
 *
 * <p>The {@link License} entity remains the source of truth for its own state
 * machine and effective-window invariants. Implementations of this policy must
 * only add rules that need facts outside the License aggregate, such as
 * Booking, Charging Session, Payment/Settlement or Compliance state.</p>
 *
 * <p>Policy methods validate only: they must not mutate or persist License or
 * any related aggregate.</p>
 */
public interface LicenseLifeCyclePolicy {

    void requireCanReactivate(License license, Instant at);

    void requireCanSuspend(License license, Instant at);

    void requireCanCancel(License license, Instant at);

    void requireCanRenew(License source, Instant at);

}
