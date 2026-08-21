package com.thang.chargeops.station.scheduler;

import com.thang.chargeops.station.repository.LicenseRepository;
import com.thang.chargeops.station.service.LicenseExpirationService;
import com.thang.chargeops.station.service.LicenseRenewalActivationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@Slf4j
public class LicenseScheduler {

    /*
     * This component performs lifecycle reconciliation only.
     *
     * The 30-day LICENSE_EXPIRING reminder must also remain a separate job/use
     * case. Add it only with the Notification/Outbox domain and an idempotency
     * guard (for example licenseId + reminderType + threshold); otherwise every
     * scheduler run can send the same reminder again.
     */

    private final LicenseRepository licenseRepository;
    private final LicenseExpirationService licenseExpirationService;
    private final LicenseRenewalActivationService licenseRenewalActivationService;
    private final Clock clock;
    private final int batchSize;

    public LicenseScheduler(
            LicenseRepository licenseRepository,
            LicenseExpirationService licenseExpirationService,
            LicenseRenewalActivationService licenseRenewalActivationService,
            Clock clock,
            @Value("${app.scheduling.license-expiration.batch-size:100}") int batchSize
    ) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("License expiration batch size must be positive");
        }

        this.licenseRepository = licenseRepository;
        this.licenseExpirationService = licenseExpirationService;
        this.licenseRenewalActivationService = licenseRenewalActivationService;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${app.scheduling.license-expiration.fixed-delay-ms:60000}",
            initialDelayString = "${app.scheduling.license-expiration.initial-delay-ms:60000}"
    )
    public void expireDueLicenses() {
        Instant now = clock.instant();
        List<UUID> candidateIds = licenseRepository.findDueNonTerminalLicenseIds(
                now,
                PageRequest.of(0, batchSize)
        );

        int expiredCount = 0;
        int conflictCount = 0;

        for (UUID licenseId : candidateIds) {
            try {
                if (licenseExpirationService.expireIfDue(licenseId, now)) {
                    expiredCount++;
                }
            } catch (OptimisticLockingFailureException exception) {
                conflictCount++;
                log.warn(
                        "Skipped automatic expiration for license {} because it was modified concurrently",
                        licenseId
                );
            }
        }

        if (!candidateIds.isEmpty()) {
            log.info(
                    "License expiration run completed: candidates={}, expired={}, conflicts={}",
                    candidateIds.size(),
                    expiredCount,
                    conflictCount
            );
        }
    }

    @Scheduled(
            fixedDelayString = "${app.scheduling.license-renewal-activation.fixed-delay-ms:60000}",
            initialDelayString = "${app.scheduling.license-renewal-activation.initial-delay-ms:30000}"
    )
    public void activateDueRenewals() {
        Instant now = clock.instant();
        // Query chỉ lấy ID theo batch để scheduler không giữ cả entity graph lâu.
        // Mỗi ID sẽ được service mở transaction riêng và re-check điều kiện.
        List<UUID> candidateIds = licenseRepository.findDuePendingRenewalActivationIds(
                now,
                PageRequest.of(0, batchSize)
        );

        int activatedCount = 0;
        int skippedCount = 0;
        int conflictCount = 0;

        for (UUID licenseId : candidateIds) {
            try {
                // false không phải lỗi: candidate có thể đã được xử lý hoặc không
                // còn đủ điều kiện trong khoảng giữa query và lúc mở transaction.
                if (licenseRenewalActivationService.activateIfDue(licenseId, now)) {
                    activatedCount++;
                } else {
                    skippedCount++;
                }
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException exception) {
                conflictCount++;
                log.warn(
                        "Skipped pending renewal activation for license {} because of a concurrent lifecycle conflict",
                        licenseId
                );
            }
        }

        if (!candidateIds.isEmpty()) {
            log.info(
                    "Renewal activation run completed: candidates={}, activated={}, skipped={}, conflicts={}",
                    candidateIds.size(),
                    activatedCount,
                    skippedCount,
                    conflictCount
            );
        }
    }
}
