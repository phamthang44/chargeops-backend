package com.thang.chargeops.station.service;

import java.time.Instant;
import java.util.UUID;

/**
 * Reconciles one due renewed License in its own transaction.
 *
 * <p>The scheduler owns batching only. This service owns the atomic lifecycle
 * work so a failure for one renewal does not roll back other candidates.</p>
 */
public interface LicenseRenewalActivationService {

    boolean activateIfDue(UUID renewedLicenseId, Instant now);
}
