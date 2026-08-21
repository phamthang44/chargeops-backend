package com.thang.chargeops.station.service;

import com.thang.chargeops.common.enums.LicenseStatus;
import com.thang.chargeops.common.enums.LicenseStatusEventType;
import com.thang.chargeops.common.enums.Plan;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.LicenseErrorCode;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.service.UserProfileService;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.dto.license.request.IssueLicenseRequest;
import com.thang.chargeops.station.dto.license.response.OwnerLicenseHistoryResponse;
import com.thang.chargeops.station.dto.license.response.OwnerLicenseResponse;
import com.thang.chargeops.station.entity.License;
import com.thang.chargeops.station.entity.LicenseStatusEvent;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.mapper.LicenseMapper;
import com.thang.chargeops.station.policy.LicenseLifeCyclePolicy;
import com.thang.chargeops.station.repository.LicenseRepository;
import com.thang.chargeops.station.service.impl.LicenseServiceImpl;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
class LicenseServiceImplTest {

    @Mock
    private StationService stationService;
    @Mock
    private LicenseMapper licenseMapper;
    @Mock
    private LicenseRepository licenseRepository;
    @Mock
    private LicenseStatusEventService licenseStatusEventService;
    @Mock
    private CurrentProfileProvider currentProfileProvider;
    @Mock
    private UserProfileService userProfileService;
    @Mock
    private LicenseLifeCyclePolicy licenseLifeCyclePolicy;

    private LicenseServiceImpl licenseService;

    @BeforeEach
    void setUp() {
        licenseService = new LicenseServiceImpl(
                stationService,
                licenseMapper,
                licenseRepository,
                licenseStatusEventService,
                currentProfileProvider,
                userProfileService,
                licenseLifeCyclePolicy
        );
    }

    @Test
    void suspendsActiveLicenseAndRecordsNormalizedAuditEvent() {
        UUID licenseId = UUID.randomUUID();
        UserProfile admin = mock(UserProfile.class);
        License license = activeLicense();

        when(currentProfileProvider.getProfileReference()).thenReturn(admin);
        when(licenseRepository.findById(licenseId)).thenReturn(Optional.of(license));

        licenseService.suspendLicense(licenseId, "  Manual compliance review  ");

        assertThat(license.getStatus()).isEqualTo(LicenseStatus.SUSPENDED);
        verify(licenseLifeCyclePolicy).requireCanSuspend(eq(license), any(Instant.class));

        ArgumentCaptor<LicenseStatusEvent> eventCaptor =
                ArgumentCaptor.forClass(LicenseStatusEvent.class);
        verify(licenseStatusEventService).recordLicenseStatusEvent(eventCaptor.capture());

        LicenseStatusEvent event = eventCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo(LicenseStatusEventType.SUSPENDED);
        assertThat(event.getFromStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(event.getToStatus()).isEqualTo(LicenseStatus.SUSPENDED);
        assertThat(event.getReason()).isEqualTo("Manual compliance review");
        assertThat(event.getPerformedBy()).isSameAs(admin);
    }

    @Test
    void mapsSuspendOutsideEffectiveWindowToBusinessConflict() {
        UUID licenseId = UUID.randomUUID();
        License expiredButNotReconciled = expiredActiveLicense();

        when(currentProfileProvider.getProfileReference()).thenReturn(mock(UserProfile.class));
        when(licenseRepository.findById(licenseId))
                .thenReturn(Optional.of(expiredButNotReconciled));

        assertThatThrownBy(() -> licenseService.suspendLicense(
                licenseId,
                "Manual compliance review"
        )).isInstanceOfSatisfying(
                AppException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(LicenseErrorCode.LICENSE_OUTSIDE_EFFECTIVE_PERIOD)
        );

        verify(licenseStatusEventService, never())
                .recordLicenseStatusEvent(any(LicenseStatusEvent.class));
    }

    @Test
    void cancelsNonTerminalLicenseAndRecordsCancelledEvent() {
        UUID licenseId = UUID.randomUUID();
        UserProfile admin = mock(UserProfile.class);
        License license = activeLicense();

        when(currentProfileProvider.getProfileReference()).thenReturn(admin);
        when(licenseRepository.findById(licenseId)).thenReturn(Optional.of(license));

        licenseService.cancelLicense(licenseId, "  Contract terminated  ");

        assertThat(license.getStatus()).isEqualTo(LicenseStatus.CANCELLED);
        verify(licenseLifeCyclePolicy).requireCanCancel(eq(license), any(Instant.class));

        ArgumentCaptor<LicenseStatusEvent> eventCaptor =
                ArgumentCaptor.forClass(LicenseStatusEvent.class);
        verify(licenseStatusEventService).recordLicenseStatusEvent(eventCaptor.capture());

        LicenseStatusEvent event = eventCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo(LicenseStatusEventType.CANCELLED);
        assertThat(event.getFromStatus()).isEqualTo(LicenseStatus.ACTIVE);
        assertThat(event.getToStatus()).isEqualTo(LicenseStatus.CANCELLED);
        assertThat(event.getReason()).isEqualTo("Contract terminated");
        assertThat(event.getPerformedBy()).isSameAs(admin);
    }

    @Test
    void mapsTerminalCancellationToInvalidTransition() {
        UUID licenseId = UUID.randomUUID();
        License license = activeLicense();
        license.cancel();

        when(currentProfileProvider.getProfileReference()).thenReturn(mock(UserProfile.class));
        when(licenseRepository.findById(licenseId)).thenReturn(Optional.of(license));

        assertThatThrownBy(() -> licenseService.cancelLicense(
                licenseId,
                "Duplicate cancellation request"
        )).isInstanceOfSatisfying(
                AppException.class,
                exception -> {
                    assertThat(exception.getErrorCode())
                            .isEqualTo(LicenseErrorCode.INVALID_STATUS_TRANSITION);
                    assertThat(exception.getMessage())
                            .contains("from CANCELLED to CANCELLED");
                }
        );

        verify(licenseStatusEventService, never())
                .recordLicenseStatusEvent(any(LicenseStatusEvent.class));
    }

    @Test
    void rejectsInvalidInternalReasonBeforeLoadingLicense() {
        when(currentProfileProvider.getProfileReference()).thenReturn(mock(UserProfile.class));

        assertThatThrownBy(() -> licenseService.suspendLicense(
                UUID.randomUUID(),
                "   "
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A reason is required for this license operation");

        verify(licenseRepository, never()).findById(any(UUID.class));
    }

    @Test
    void mapsOnlyTheActiveLicenseUniqueConstraintToBusinessConflict() {
        UUID stationId = UUID.randomUUID();
        prepareIssue(stationId);

        ConstraintViolationException constraintViolation = mock(ConstraintViolationException.class);
        when(constraintViolation.getConstraintName())
                .thenReturn("ux_licenses_one_active_per_station");
        when(licenseRepository.save(any(License.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate active license", constraintViolation));

        assertThatThrownBy(() -> licenseService.issueLicense(
                stationId,
                new IssueLicenseRequest(Plan.MONTHLY)
        )).isInstanceOfSatisfying(
                AppException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(LicenseErrorCode.ACTIVE_LICENSE_ALREADY_EXISTS)
        );
    }

    @Test
    void doesNotMislabelUnrelatedDataIntegrityFailuresAsDuplicateLicense() {
        UUID stationId = UUID.randomUUID();
        prepareIssue(stationId);

        DataIntegrityViolationException failure =
                new DataIntegrityViolationException("unrelated foreign key failure");
        when(licenseRepository.save(any(License.class))).thenThrow(failure);

        assertThatThrownBy(() -> licenseService.issueLicense(
                stationId,
                new IssueLicenseRequest(Plan.YEARLY)
        )).isSameAs(failure);
    }

    @Test
    void issuesLicenseWithCodeFromDatabaseSequence() {
        UUID stationId = UUID.randomUUID();
        prepareIssue(stationId);

        when(licenseRepository.save(any(License.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        licenseService.issueLicense(
                stationId,
                new IssueLicenseRequest(Plan.MONTHLY)
        );

        ArgumentCaptor<License> licenseCaptor = ArgumentCaptor.forClass(License.class);
        verify(licenseRepository).save(licenseCaptor.capture());
        assertThat(licenseCaptor.getValue().getLicenseCode()).isEqualTo("LIC-001000");
    }

    @Test
    void returnsCurrentLicenseAndHistoryForTheOwningStationOwner() {
        UUID stationId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UserProfile owner = mock(UserProfile.class);
        Station station = new Station();
        station.setId(stationId);
        station.setOwner(owner);
        License currentLicense = mock(License.class);
        List<License> licenses = List.of(currentLicense);
        OwnerLicenseHistoryResponse history = OwnerLicenseHistoryResponse.builder().build();
        OwnerLicenseResponse expected = OwnerLicenseResponse.builder().build();

        when(owner.getId()).thenReturn(ownerId);
        when(currentProfileProvider.requireProfile()).thenReturn(owner);
        when(stationService.getStationById(stationId)).thenReturn(station);
        when(licenseRepository.findOwnerStationLicenseHistory(stationId, ownerId))
                .thenReturn(licenses);
        when(currentLicense.isEffectivelyActiveAt(any(Instant.class))).thenReturn(true);
        when(licenseMapper.toOwnerLicenseHistoryResponseList(licenses))
                .thenReturn(List.of(history));
        when(licenseMapper.toOwnerLicenseResponse(currentLicense)).thenReturn(expected);

        OwnerLicenseResponse actual = licenseService.getMyLicenseByStationId(stationId);

        assertThat(actual).isSameAs(expected);
        assertThat(actual.getHistories()).containsExactly(history);
    }

    @Test
    void rejectsAnOwnerTryingToReadAnotherOwnersStationLicense() {
        UUID stationId = UUID.randomUUID();
        UserProfile currentOwner = mock(UserProfile.class);
        UserProfile stationOwner = mock(UserProfile.class);
        Station station = new Station();
        station.setOwner(stationOwner);

        when(currentOwner.getId()).thenReturn(UUID.randomUUID());
        when(stationOwner.getId()).thenReturn(UUID.randomUUID());
        when(currentProfileProvider.requireProfile()).thenReturn(currentOwner);
        when(stationService.getStationById(stationId)).thenReturn(station);

        assertThatThrownBy(() -> licenseService.getMyLicenseByStationId(stationId))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(StationErrorCode.STATION_ACCESS_DENIED)
                );

        verify(licenseRepository, never()).findOwnerStationLicenseHistory(
                any(UUID.class),
                any(UUID.class)
        );
    }

    private void prepareIssue(UUID stationId) {
        Station station = new Station();
        station.setId(stationId);
        UserProfile admin = mock(UserProfile.class);

        when(currentProfileProvider.getProfileReference()).thenReturn(admin);
        when(stationService.getStationById(stationId)).thenReturn(station);
        when(licenseRepository.existsPersistedActiveLicenseForStation(stationId)).thenReturn(false);
        when(licenseRepository.nextLicenseCodeSequence()).thenReturn(1000L);
    }

    private License activeLicense() {
        Instant startAt = Instant.now().minus(1, ChronoUnit.MINUTES);
        License license = License.issue(
                new Station(),
                Plan.MONTHLY,
                startAt,
                "LIC-001000"
        );
        license.activate(startAt.plusSeconds(1));
        return license;
    }

    private License expiredActiveLicense() {
        Instant startAt = Instant.now().minus(60, ChronoUnit.DAYS);
        License license = License.issue(
                new Station(),
                Plan.MONTHLY,
                startAt,
                "LIC-001001"
        );
        license.activate(startAt.plusSeconds(1));
        return license;
    }
}
