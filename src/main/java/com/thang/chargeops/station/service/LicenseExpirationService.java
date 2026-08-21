package com.thang.chargeops.station.service;

import java.time.Instant;
import java.util.UUID;

public interface LicenseExpirationService {

    boolean expireIfDue(UUID licenseId, Instant now);
}
