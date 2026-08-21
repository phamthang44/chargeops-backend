package com.thang.chargeops.station.scheduler;

import com.thang.chargeops.station.repository.LicenseRepository;
import com.thang.chargeops.station.service.LicenseExpirationService;
import com.thang.chargeops.station.service.LicenseRenewalActivationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LicenseSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-08-17T03:00:00Z");

    @Mock
    private LicenseRepository licenseRepository;
    @Mock
    private LicenseExpirationService licenseExpirationService;
    @Mock
    private LicenseRenewalActivationService licenseRenewalActivationService;

    private LicenseScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new LicenseScheduler(
                licenseRepository,
                licenseExpirationService,
                licenseRenewalActivationService,
                Clock.fixed(NOW, ZoneOffset.UTC),
                50
        );
    }

    @Test
    void expiresCandidatesUsingOneConsistentTimestamp() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        when(licenseRepository.findDueNonTerminalLicenseIds(eq(NOW), samePage(50)))
                .thenReturn(List.of(firstId, secondId));
        when(licenseExpirationService.expireIfDue(firstId, NOW)).thenReturn(true);
        when(licenseExpirationService.expireIfDue(secondId, NOW)).thenReturn(true);

        scheduler.expireDueLicenses();

        verify(licenseExpirationService).expireIfDue(firstId, NOW);
        verify(licenseExpirationService).expireIfDue(secondId, NOW);
    }

    @Test
    void continuesWithOtherCandidatesAfterOptimisticLockConflict() {
        UUID conflictedId = UUID.randomUUID();
        UUID nextId = UUID.randomUUID();
        when(licenseRepository.findDueNonTerminalLicenseIds(eq(NOW), samePage(50)))
                .thenReturn(List.of(conflictedId, nextId));
        when(licenseExpirationService.expireIfDue(conflictedId, NOW))
                .thenThrow(new OptimisticLockingFailureException("concurrent update"));
        when(licenseExpirationService.expireIfDue(nextId, NOW)).thenReturn(true);

        scheduler.expireDueLicenses();

        verify(licenseExpirationService).expireIfDue(nextId, NOW);
    }

    @Test
    void activatesDueRenewalsUsingOneConsistentTimestamp() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        when(licenseRepository.findDuePendingRenewalActivationIds(eq(NOW), samePage(50)))
                .thenReturn(List.of(firstId, secondId));
        when(licenseRenewalActivationService.activateIfDue(firstId, NOW)).thenReturn(true);
        when(licenseRenewalActivationService.activateIfDue(secondId, NOW)).thenReturn(true);

        scheduler.activateDueRenewals();

        verify(licenseRenewalActivationService).activateIfDue(firstId, NOW);
        verify(licenseRenewalActivationService).activateIfDue(secondId, NOW);
    }

    @Test
    void continuesRenewalBatchAfterOptimisticLockConflict() {
        UUID conflictedId = UUID.randomUUID();
        UUID nextId = UUID.randomUUID();
        when(licenseRepository.findDuePendingRenewalActivationIds(eq(NOW), samePage(50)))
                .thenReturn(List.of(conflictedId, nextId));
        when(licenseRenewalActivationService.activateIfDue(conflictedId, NOW))
                .thenThrow(new OptimisticLockingFailureException("concurrent update"));
        when(licenseRenewalActivationService.activateIfDue(nextId, NOW)).thenReturn(true);

        scheduler.activateDueRenewals();

        verify(licenseRenewalActivationService).activateIfDue(nextId, NOW);
    }

    private Pageable samePage(int pageSize) {
        return org.mockito.ArgumentMatchers.argThat(
                pageable -> pageable.getPageNumber() == 0
                        && pageable.getPageSize() == pageSize
        );
    }
}
