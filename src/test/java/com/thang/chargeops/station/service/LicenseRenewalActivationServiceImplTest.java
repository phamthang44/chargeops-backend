package com.thang.chargeops.station.service;

import com.thang.chargeops.common.enums.LicenseStatus;
import com.thang.chargeops.common.enums.LicenseStatusActorType;
import com.thang.chargeops.common.enums.LicenseStatusEventType;
import com.thang.chargeops.common.enums.Plan;
import com.thang.chargeops.station.entity.License;
import com.thang.chargeops.station.entity.LicenseStatusEvent;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.repository.LicenseRepository;
import com.thang.chargeops.station.service.impl.LicenseRenewalActivationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LicenseRenewalActivationServiceImplTest {

    private static final Instant SOURCE_START = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private LicenseRepository licenseRepository;
    @Mock
    private LicenseStatusEventService licenseStatusEventService;

    private LicenseRenewalActivationServiceImpl activationService;

    @BeforeEach
    void setUp() {
        activationService = new LicenseRenewalActivationServiceImpl(
                licenseRepository,
                licenseStatusEventService
        );
    }

    @Test
    void expiresSourceAndActivatesDueRenewalAtomically() {
        UUID renewalId = UUID.randomUUID();
        RenewalFixture fixture = pendingRenewal();
        Instant now = fixture.renewal().getStartAt();
        when(licenseRepository.findById(renewalId))
                .thenReturn(Optional.of(fixture.renewal()));

        boolean activated = activationService.activateIfDue(renewalId, now);

        assertThat(activated).isTrue();
        assertThat(fixture.source().getStatus()).isEqualTo(LicenseStatus.EXPIRED);
        assertThat(fixture.renewal().getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        verify(licenseRepository).flush();

        ArgumentCaptor<LicenseStatusEvent> eventCaptor =
                ArgumentCaptor.forClass(LicenseStatusEvent.class);
        verify(licenseStatusEventService, times(2))
                .recordLicenseStatusEvent(eventCaptor.capture());

        List<LicenseStatusEvent> events = eventCaptor.getAllValues();
        assertThat(events)
                .extracting(LicenseStatusEvent::getEventType)
                .containsExactly(
                        LicenseStatusEventType.EXPIRED,
                        LicenseStatusEventType.ACTIVATED
                );
        assertThat(events)
                .extracting(LicenseStatusEvent::getActorType)
                .containsOnly(LicenseStatusActorType.SYSTEM);
        assertThat(events)
                .extracting(LicenseStatusEvent::getPerformedAt)
                .containsOnly(now);
    }

    @Test
    void activatesWhenSourceWasAlreadyExpiredByExpirationScheduler() {
        UUID renewalId = UUID.randomUUID();
        RenewalFixture fixture = pendingRenewal();
        Instant now = fixture.renewal().getStartAt();
        fixture.source().markExpired(now);
        when(licenseRepository.findById(renewalId))
                .thenReturn(Optional.of(fixture.renewal()));

        boolean activated = activationService.activateIfDue(renewalId, now);

        assertThat(activated).isTrue();
        assertThat(fixture.renewal().getStatus()).isEqualTo(LicenseStatus.ACTIVE);

        ArgumentCaptor<LicenseStatusEvent> eventCaptor =
                ArgumentCaptor.forClass(LicenseStatusEvent.class);
        verify(licenseStatusEventService).recordLicenseStatusEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getEventType())
                .isEqualTo(LicenseStatusEventType.ACTIVATED);
    }

    @Test
    void skipsRenewalBeforeItsEffectiveWindowStarts() {
        UUID renewalId = UUID.randomUUID();
        RenewalFixture fixture = pendingRenewal();
        Instant beforeStart = fixture.renewal().getStartAt().minusSeconds(1);
        when(licenseRepository.findById(renewalId))
                .thenReturn(Optional.of(fixture.renewal()));

        boolean activated = activationService.activateIfDue(renewalId, beforeStart);

        assertThat(activated).isFalse();
        assertThat(fixture.source().getStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(fixture.renewal().getStatus()).isEqualTo(LicenseStatus.PENDING);
        verify(licenseStatusEventService, never())
                .recordLicenseStatusEvent(any(LicenseStatusEvent.class));
        verify(licenseRepository, never()).flush();
    }

    @Test
    void ignoresPendingLicenseThatWasNotCreatedByRenewal() {
        UUID licenseId = UUID.randomUUID();
        Instant now = Instant.parse("2026-02-01T00:00:00Z");
        License scheduledFirstIssue = License.issue(
                station(),
                Plan.MONTHLY,
                now,
                "LIC-001002"
        );
        when(licenseRepository.findById(licenseId))
                .thenReturn(Optional.of(scheduledFirstIssue));

        boolean activated = activationService.activateIfDue(licenseId, now);

        assertThat(activated).isFalse();
        assertThat(scheduledFirstIssue.getStatus()).isEqualTo(LicenseStatus.PENDING);
        verify(licenseStatusEventService, never())
                .recordLicenseStatusEvent(any(LicenseStatusEvent.class));
    }

    private RenewalFixture pendingRenewal() {
        Station station = station();
        License source = License.issue(
                station,
                Plan.MONTHLY,
                SOURCE_START,
                "LIC-001000"
        );
        source.activate(SOURCE_START);

        License renewal = License.renewFrom(
                source,
                Plan.MONTHLY,
                source.getExpiresAt(),
                "LIC-001001"
        );
        return new RenewalFixture(source, renewal);
    }

    private Station station() {
        Station station = new Station();
        station.setId(UUID.randomUUID());
        return station;
    }

    private record RenewalFixture(License source, License renewal) {
    }
}
