package com.thang.chargeops.station.service.impl;

import com.thang.chargeops.common.enums.LicenseStatus;
import com.thang.chargeops.common.enums.LicenseStatusEventType;
import com.thang.chargeops.station.entity.License;
import com.thang.chargeops.station.entity.LicenseStatusEvent;
import com.thang.chargeops.station.repository.LicenseRepository;
import com.thang.chargeops.station.service.LicenseExpirationService;
import com.thang.chargeops.station.service.LicenseStatusEventService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LicenseExpirationServiceImpl implements LicenseExpirationService {

    private static final String EXPIRATION_REASON = "License validity period elapsed";

    private final LicenseRepository licenseRepository;
    private final LicenseStatusEventService licenseStatusEventService;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expireIfDue(UUID licenseId, Instant now) {
        License license = licenseRepository.findById(licenseId).orElse(null);

        if (license == null
                // PENDING chưa từng có hiệu lực: phase hiện tại không tự suy diễn
                // processing delay thành business transition PENDING -> EXPIRED.
                || license.getStatus() == LicenseStatus.PENDING
                || license.getStatus() == LicenseStatus.CANCELLED
                || license.getStatus() == LicenseStatus.EXPIRED
                || license.getExpiresAt().isAfter(now)) {
            return false;
        }

        LicenseStatus fromStatus = license.getStatus();
        license.markExpired(now);

        licenseStatusEventService.recordLicenseStatusEvent(
                LicenseStatusEvent.recordedBySystem(
                        license,
                        LicenseStatusEventType.EXPIRED,
                        fromStatus,
                        license.getStatus(),
                        now,
                        EXPIRATION_REASON
                )
        );

        return true;
    }
}
