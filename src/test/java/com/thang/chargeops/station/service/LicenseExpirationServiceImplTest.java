package com.thang.chargeops.station.service;

import com.thang.chargeops.common.enums.LicenseStatus;
import com.thang.chargeops.common.enums.LicenseStatusActorType;
import com.thang.chargeops.common.enums.LicenseStatusEventType;
import com.thang.chargeops.common.enums.Plan;
import com.thang.chargeops.station.entity.License;
import com.thang.chargeops.station.entity.LicenseStatusEvent;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.repository.LicenseRepository;
import com.thang.chargeops.station.service.impl.LicenseExpirationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LicenseExpirationServiceImplTest {

    @Mock
    private LicenseRepository licenseRepository;
    @Mock
    private LicenseStatusEventService licenseStatusEventService;

    private LicenseExpirationServiceImpl expirationService;

    @BeforeEach
    void setUp() {
        expirationService = new LicenseExpirationServiceImpl(
                licenseRepository,
                licenseStatusEventService
        );
    }

    @Test
    void expiresDueActiveLicenseAndRecordsSystemEvent() {
        UUID licenseId = UUID.randomUUID();
        Instant startAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant now = Instant.parse("2026-02-02T00:00:00Z");
        License license = activeLicense(startAt);
        when(licenseRepository.findById(licenseId)).thenReturn(Optional.of(license));

        boolean expired = expirationService.expireIfDue(licenseId, now);

        assertThat(expired).isTrue();
        assertThat(license.getStatus()).isEqualTo(LicenseStatus.EXPIRED);

        ArgumentCaptor<LicenseStatusEvent> eventCaptor =
                ArgumentCaptor.forClass(LicenseStatusEvent.class);
        verify(licenseStatusEventService).recordLicenseStatusEvent(eventCaptor.capture());

        LicenseStatusEvent event = eventCaptor.getValue();
        assertThat(event.getLicense()).isSameAs(license);
        assertThat(event.getEventType()).isEqualTo(LicenseStatusEventType.EXPIRED);
        assertThat(event.getFromStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(event.getToStatus()).isEqualTo(LicenseStatus.EXPIRED);
        assertThat(event.getActorType()).isEqualTo(LicenseStatusActorType.SYSTEM);
        assertThat(event.getPerformedBy()).isNull();
        assertThat(event.getPerformedAt()).isEqualTo(now);
        assertThat(event.getReason()).isEqualTo("License validity period elapsed");
    }

    @Test
    void skipsLicenseThatIsNotDueYet() {
        UUID licenseId = UUID.randomUUID();
        Instant startAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant now = Instant.parse("2026-01-15T00:00:00Z");
        License license = activeLicense(startAt);
        when(licenseRepository.findById(licenseId)).thenReturn(Optional.of(license));

        boolean expired = expirationService.expireIfDue(licenseId, now);

        assertThat(expired).isFalse();
        assertThat(license.getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        verify(licenseStatusEventService, never()).recordLicenseStatusEvent(any(LicenseStatusEvent.class));
    }

    @Test
    void expiresSuspendedLicenseBecauseItsValidityPeriodKeepsRunning() {
        UUID licenseId = UUID.randomUUID();
        Instant startAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant suspendedAt = Instant.parse("2026-01-15T00:00:00Z");
        Instant now = Instant.parse("2026-02-02T00:00:00Z");
        License license = activeLicense(startAt);
        license.suspend(suspendedAt);
        when(licenseRepository.findById(licenseId)).thenReturn(Optional.of(license));

        boolean expired = expirationService.expireIfDue(licenseId, now);

        assertThat(expired).isTrue();
        assertThat(license.getStatus()).isEqualTo(LicenseStatus.EXPIRED);

        ArgumentCaptor<LicenseStatusEvent> eventCaptor =
                ArgumentCaptor.forClass(LicenseStatusEvent.class);
        verify(licenseStatusEventService).recordLicenseStatusEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getFromStatus()).isEqualTo(LicenseStatus.SUSPENDED);
    }

    @Test
    void keepsNeverActivatedPendingLicenseForReconciliation() {
        UUID licenseId = UUID.randomUUID();
        Instant startAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant now = Instant.parse("2026-02-02T00:00:00Z");
        License pendingLicense = License.issue(
                new Station(),
                Plan.MONTHLY,
                startAt,
                "LIC-001001"
        );
        when(licenseRepository.findById(licenseId)).thenReturn(Optional.of(pendingLicense));

        boolean expired = expirationService.expireIfDue(licenseId, now);

        assertThat(expired).isFalse();
        assertThat(pendingLicense.getStatus()).isEqualTo(LicenseStatus.PENDING);
        verify(licenseStatusEventService, never())
                .recordLicenseStatusEvent(any(LicenseStatusEvent.class));
    }

    @Test
    void skipsLicenseThatBecameTerminalAfterCandidateQuery() {
        UUID licenseId = UUID.randomUUID();
        Instant startAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant now = Instant.parse("2026-02-02T00:00:00Z");
        License license = activeLicense(startAt);
        license.cancel();
        when(licenseRepository.findById(licenseId)).thenReturn(Optional.of(license));

        boolean expired = expirationService.expireIfDue(licenseId, now);

        assertThat(expired).isFalse();
        assertThat(license.getStatus()).isEqualTo(LicenseStatus.CANCELLED);
        verify(licenseStatusEventService, never()).recordLicenseStatusEvent(any(LicenseStatusEvent.class));
    }

    private License activeLicense(Instant startAt) {
        License license = License.issue(
                new Station(),
                Plan.MONTHLY,
                startAt,
                "LIC-001000"
        );
        license.activate(startAt);
        return license;
    }
}
