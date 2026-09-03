package com.thang.chargeops.station.service;

import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.common.enums.StationOperatingState;
import com.thang.chargeops.common.enums.StationOperationalStatus;
import com.thang.chargeops.common.enums.StationStatusEventType;
import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.ProfileErrorCode;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.exception.errormessage.StationErrorMessage;
import com.thang.chargeops.location.entity.AdministrativeWard;
import com.thang.chargeops.location.service.AdministrativeLocationService;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.dto.station.request.RegisterStationRequest;
import com.thang.chargeops.station.dto.station.request.RejectStationRequest;
import com.thang.chargeops.station.dto.station.request.ChangeStationOperationalStatusRequest;
import com.thang.chargeops.station.dto.license.response.LicenseSummaryResponse;
import com.thang.chargeops.station.dto.station.filter.StationFilter;
import com.thang.chargeops.station.dto.station.response.AdminStationDetailResponse;
import com.thang.chargeops.station.dto.station.response.AdminStationListItemResponse;
import com.thang.chargeops.station.dto.station.response.OwnerStationSummaryResponse;
import com.thang.chargeops.station.dto.station.response.StationApprovalSummaryResponse;
import com.thang.chargeops.station.dto.station.response.StationCreatedResponse;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.entity.License;
import com.thang.chargeops.station.entity.StationOperatingSchedule;
import com.thang.chargeops.station.entity.StationOperationalStatusEvent;
import com.thang.chargeops.station.mapper.StationMapper;
import com.thang.chargeops.station.policy.StationApprovalPolicy;
import com.thang.chargeops.station.projection.OwnerStationSummaryProjection;
import com.thang.chargeops.station.projection.StationApprovalSummaryProjection;
import com.thang.chargeops.station.repository.LicenseRepository;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.service.impl.StationServiceImpl;
import com.thang.chargeops.station.service.support.StationOperatingStatus;
import com.thang.chargeops.station.service.support.StationEffectiveStateResolver;
import com.thang.chargeops.station.service.usecase.AdminStationQueryUseCase;
import com.thang.chargeops.station.service.usecase.OwnerStationUseCase;
import com.thang.chargeops.station.service.usecase.StationLifecycleUseCase;
import com.thang.chargeops.station.service.usecase.StationRegistrationUseCase;
import com.thang.chargeops.station.policy.StationOperationalStatusPolicy;
import com.thang.chargeops.station.repository.StationOperationalStatusEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StationServiceImplTest {

    @Mock
    private StationRepository stationRepository;

    @Mock
    private StationMapper stationMapper;

    @Mock
    private CurrentProfileProvider currentProfileProvider;

    @Mock
    private AdministrativeLocationService administrativeLocationService;

    @Mock
    private StationStatusHistoryService stationStatusHistoryService;

    @Mock
    private StationApprovalPolicy stationApprovalPolicy;

    @Mock
    private LicenseRepository licenseRepository;

    @Mock
    private com.thang.chargeops.station.repository.StationAssetRepository stationAssetRepository;

    @Mock
    private com.thang.chargeops.station.repository.StationOperatingScheduleRepository stationOperatingScheduleRepository;

    @Mock
    private com.thang.chargeops.station.repository.StationOperatingPeriodRepository stationOperatingPeriodRepository;

    @Mock
    private StationEffectiveStateResolver effectiveStateResolver;

    @Mock
    private StationOperationalStatusPolicy operationalStatusPolicy;

    @Mock
    private StationOperationalStatusEventRepository operationalStatusEventRepository;

    @Mock
    private RegisterStationRequest request;

    private StationServiceImpl stationService;

    @BeforeEach
    void setUp() {
        stationService = new StationServiceImpl(
                new StationRegistrationUseCase(
                        stationRepository,
                        stationMapper,
                        currentProfileProvider,
                        administrativeLocationService,
                        stationStatusHistoryService
                ),
                new StationLifecycleUseCase(
                        stationRepository,
                        stationMapper,
                        currentProfileProvider,
                        stationStatusHistoryService,
                        stationApprovalPolicy,
                        licenseRepository,
                        stationOperatingScheduleRepository
                ),
                new OwnerStationUseCase(
                        stationRepository,
                        stationMapper,
                        currentProfileProvider,
                        stationOperatingScheduleRepository,
                        effectiveStateResolver,
                        operationalStatusPolicy,
                        operationalStatusEventRepository
                ),
                new AdminStationQueryUseCase(
                        stationRepository,
                        stationMapper,
                        licenseRepository,
                        stationAssetRepository,
                        stationOperatingScheduleRepository,
                        stationOperatingPeriodRepository
                )
        );
    }

    @Test
    void createsPendingStationForCurrentActiveOwner() {
        UserProfile owner = UserProfile.builder().status(UserStatus.ACTIVE).build();
        Station station = new Station();
        StationCreatedResponse expected = new StationCreatedResponse(
                null,
                "ST-1000",
                "Station",
                StationStatus.PENDING_APPROVAL,
                Instant.now()
        );
        AdministrativeWard ward = org.mockito.Mockito.mock(AdministrativeWard.class);

        when(currentProfileProvider.requireProfile()).thenReturn(owner);
        when(request.wardCode()).thenReturn("00001");
        when(request.provinceCode()).thenReturn("01");
        when(administrativeLocationService.requireWard("00001")).thenReturn(ward);
        when(stationMapper.toStationEntity(request)).thenReturn(station);
        when(stationRepository.nextStationCodeSequence()).thenReturn(1000L);
        when(stationRepository.save(station)).thenReturn(station);
        when(stationMapper.toStationCreatedResponse(station)).thenReturn(expected);

        StationCreatedResponse actual = stationService.createStationRegistration(request);

        assertThat(actual).isSameAs(expected);
        assertThat(station.getStationCode()).isEqualTo("ST-1000");
        assertThat(station.getOwner()).isSameAs(owner);
        assertThat(station.getWard()).isSameAs(ward);
        assertThat(station.getStatus()).isEqualTo(StationStatus.PENDING_APPROVAL);
        verify(currentProfileProvider).requireProfile();
        verify(administrativeLocationService).requireWardBelongsToProvince(ward, "01");
        verify(stationRepository).save(station);
        verify(stationStatusHistoryService).recordTransition(
                station,
                StationStatusEventType.SUBMITTED,
                null,
                owner,
                null
        );
    }

    @Test
    void approvesPendingStationAndRecordsTransition() {
        UUID stationId = UUID.randomUUID();
        Station station = new Station();
        station.setStatus(StationStatus.PENDING_APPROVAL);
        UserProfile admin = UserProfile.builder().status(UserStatus.ACTIVE).build();

        when(stationRepository.findById(stationId)).thenReturn(java.util.Optional.of(station));
        when(currentProfileProvider.requireProfile()).thenReturn(admin);
        when(stationOperatingScheduleRepository.findActiveByStationId(
                eq(stationId),
                any(Instant.class)
        )).thenReturn(java.util.Optional.of(mock(StationOperatingSchedule.class)));

        stationService.approveStation(stationId);

        assertThat(station.getStatus()).isEqualTo(StationStatus.ACTIVE);
        verify(stationRepository).findById(stationId);
        verify(stationApprovalPolicy).requireCanBeApproved(station);
        verify(stationStatusHistoryService).recordTransition(
                station,
                StationStatusEventType.APPROVED,
                StationStatus.PENDING_APPROVAL,
                admin,
                null
        );
    }

    @Test
    void rejectsApprovalWhenOperatingScheduleIsMissing() {
        UUID stationId = UUID.randomUUID();
        Station station = new Station();
        station.setStatus(StationStatus.PENDING_APPROVAL);
        UserProfile admin = UserProfile.builder().status(UserStatus.ACTIVE).build();

        when(stationRepository.findById(stationId))
                .thenReturn(java.util.Optional.of(station));
        when(currentProfileProvider.requireProfile()).thenReturn(admin);
        when(stationOperatingScheduleRepository.findActiveByStationId(
                eq(stationId),
                any(Instant.class)
        )).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> stationService.approveStation(stationId))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(StationErrorCode.STATION_OPERATING_SCHEDULE_REQUIRED)
                );

        assertThat(station.getStatus()).isEqualTo(StationStatus.PENDING_APPROVAL);
        verifyNoInteractions(stationStatusHistoryService);
    }

    @Test
    void rejectsPendingStationAndRecordsTransition() {
        UUID stationId = UUID.randomUUID();
        Station station = new Station();
        station.setStatus(StationStatus.PENDING_APPROVAL);
        UserProfile admin = UserProfile.builder().status(UserStatus.ACTIVE).build();
        RejectStationRequest rejectRequest = mock(RejectStationRequest.class);

        when(stationRepository.findById(stationId)).thenReturn(java.util.Optional.of(station));
        when(currentProfileProvider.requireProfile()).thenReturn(admin);
        when(rejectRequest.getReason()).thenReturn("Invalid location document");

        stationService.rejectStation(stationId, rejectRequest);

        assertThat(station.getStatus()).isEqualTo(StationStatus.REJECTED);
        verify(stationRepository).findById(stationId);
        verify(stationApprovalPolicy).requireCanBeRejected(station, "Invalid location document");
        verify(stationStatusHistoryService).recordTransition(
                station,
                StationStatusEventType.REJECTED,
                StationStatus.PENDING_APPROVAL,
                admin,
                "Invalid location document"
        );
    }

    @Test
    void rejectsCurrentOwnerWhenProfileIsNotActive() {
        UserProfile owner = UserProfile.builder().status(UserStatus.SUSPENDED).build();
        when(currentProfileProvider.requireProfile()).thenReturn(owner);

        assertThatThrownBy(() -> stationService.createStationRegistration(request))
                .isInstanceOfSatisfying(AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ProfileErrorCode.PROFILE_NOT_ACTIVE));

        verifyNoInteractions(administrativeLocationService, stationMapper, stationRepository);
    }

    @Test
    void convertsFirstClientPageAndReturnsOnlyCurrentOwnersStations() {
        UUID ownerId = UUID.randomUUID();
        UUID stationId = UUID.randomUUID();
        UserProfile owner = mock(UserProfile.class);
        OwnerStationSummaryProjection station = mock(OwnerStationSummaryProjection.class);
        StationOperatingStatus operatingStatus =
                StationOperatingStatus.unavailableByPlatform(false);
        OwnerStationSummaryResponse expected = new OwnerStationSummaryResponse(
                stationId,
                "ST-0001",
                "Station",
                "123 Main Street",
                "Ha Noi",
                "Cau Giay Ward",
                3,
                StationStatus.PENDING_APPROVAL,
                null,
                0,
                0,
                StationOperationalStatus.PAUSED,
                null,
                false,
                StationOperatingState.UNAVAILABLE_BY_PLATFORM,
                false
        );
        Page<OwnerStationSummaryProjection> stationPage = new PageImpl<>(
                List.of(station),
                PageRequest.of(0, 20),
                1
        );

        when(currentProfileProvider.requireProfile()).thenReturn(owner);
        when(owner.getId()).thenReturn(ownerId);
        when(station.getId()).thenReturn(stationId);
        when(station.getStatus()).thenReturn(StationStatus.PENDING_APPROVAL);
        when(station.getOperationalStatus()).thenReturn(StationOperationalStatus.PAUSED);
        when(stationRepository.findOwnerStationSummaries(
                eq(ownerId),
                any(Instant.class),
                any(Pageable.class)
        ))
                .thenReturn(stationPage);
        when(stationOperatingScheduleRepository.findActiveByStationIds(
                eq(List.of(stationId)),
                any(Instant.class)
        )).thenReturn(List.of());
        when(effectiveStateResolver.resolve(
                eq(false),
                eq(StationOperationalStatus.PAUSED),
                org.mockito.ArgumentMatchers.isNull(),
                any(Instant.class)
        ))
                .thenReturn(operatingStatus);
        when(stationMapper.toOwnerStationSummaryResponse(station, operatingStatus))
                .thenReturn(expected);

        Page<OwnerStationSummaryResponse> actual = stationService.getMyStations(1, 20);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(stationRepository).findOwnerStationSummaries(
                eq(ownerId),
                any(Instant.class),
                pageableCaptor.capture()
        );
        Pageable requestedPage = pageableCaptor.getValue();

        assertThat(requestedPage.getPageNumber()).isZero();
        assertThat(requestedPage.getPageSize()).isEqualTo(20);
        assertThat(requestedPage.getSort().getOrderFor("createdAt"))
                .extracting(Sort.Order::getDirection)
                .isEqualTo(Sort.Direction.DESC);
        assertThat(actual.getContent()).containsExactly(expected);
        assertThat(actual.getTotalElements()).isEqualTo(1);
        verify(stationMapper).toOwnerStationSummaryResponse(station, operatingStatus);
    }

    @Test
    void convertsFirstClientPageAndReturnsOnlyPendingApprovalStations() {
        StationApprovalSummaryProjection station = mock(StationApprovalSummaryProjection.class);
        Instant submittedAt = Instant.now();
        StationApprovalSummaryResponse expected = new StationApprovalSummaryResponse(
                UUID.randomUUID(),
                "ST-0002",
                "Pending Station",
                "Owner",
                "Da Nang",
                4,
                submittedAt
        );
        Page<StationApprovalSummaryProjection> stationPage = new PageImpl<>(
                List.of(station),
                PageRequest.of(0, 20),
                1
        );

        when(stationRepository.findStationApprovalSummaries(
                eq(StationStatus.PENDING_APPROVAL),
                any(Pageable.class)
        )).thenReturn(stationPage);
        when(stationMapper.toStationApprovalSummaryResponse(station)).thenReturn(expected);

        Page<StationApprovalSummaryResponse> actual = stationService.getStationApprovals(1, 20);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(stationRepository).findStationApprovalSummaries(
                eq(StationStatus.PENDING_APPROVAL),
                pageableCaptor.capture()
        );
        Pageable requestedPage = pageableCaptor.getValue();

        assertThat(requestedPage.getPageNumber()).isZero();
        assertThat(requestedPage.getPageSize()).isEqualTo(20);
        assertThat(requestedPage.getSort().getOrderFor("createdAt"))
                .extracting(Sort.Order::getDirection)
                .isEqualTo(Sort.Direction.DESC);
        assertThat(actual.getContent()).containsExactly(expected);
        assertThat(actual.getTotalElements()).isEqualTo(1);
        verify(stationMapper).toStationApprovalSummaryResponse(station);
        verifyNoInteractions(currentProfileProvider);
    }

    @Test
    void filtersAdminStationsAndLoadsLicenseSummariesInOneBatch() {
        UUID stationId = UUID.randomUUID();
        Station station = new Station();
        station.setId(stationId);
        License activeLicense = mock(License.class);
        LicenseSummaryResponse licenseSummary = new LicenseSummaryResponse(
                com.thang.chargeops.common.enums.Plan.MONTHLY,
                Instant.parse("2026-09-21T00:00:00Z")
        );
        AdminStationListItemResponse expected = new AdminStationListItemResponse(
                stationId,
                "ST-0001",
                "Station",
                "Address",
                "Province",
                "Ward",
                UUID.randomUUID(),
                "Owner",
                "owner@chargeops.test",
                "0900000000",
                2,
                StationStatus.ACTIVE,
                Instant.now(),
                licenseSummary
        );
        Page<Station> stationPage = new PageImpl<>(
                List.of(station),
                PageRequest.of(0, 20),
                1
        );
        StationFilter filter = new StationFilter();
        filter.setSearch("station");

        when(stationRepository.findAll(
                any(Specification.class),
                any(Pageable.class)
        )).thenReturn(stationPage);
        when(activeLicense.getStation()).thenReturn(station);
        when(activeLicense.getPlan()).thenReturn(licenseSummary.plan());
        when(activeLicense.getExpiresAt()).thenReturn(licenseSummary.expiresAt());
        when(licenseRepository.findActiveByStationIds(
                eq(List.of(stationId)),
                any(Instant.class)
        )).thenReturn(List.of(activeLicense));
        when(stationMapper.toAdminStationListItemResponse(station, licenseSummary))
                .thenReturn(expected);

        Page<AdminStationListItemResponse> actual =
                stationService.getAdminStations(1, 20, filter);

        assertThat(actual.getContent()).containsExactly(expected);
        verify(licenseRepository).findActiveByStationIds(
                eq(List.of(stationId)),
                any(Instant.class)
        );
        verify(stationMapper).toAdminStationListItemResponse(station, licenseSummary);
    }

    @Test
    void suspendsActiveStationAndRecordsTransition() {
        UUID stationId = UUID.randomUUID();
        Station station = new Station();
        station.setStatus(StationStatus.ACTIVE);
        UserProfile admin = UserProfile.builder().status(UserStatus.ACTIVE).build();

        when(stationRepository.findById(stationId)).thenReturn(java.util.Optional.of(station));
        when(currentProfileProvider.requireProfile()).thenReturn(admin);

        stationService.suspendStation(stationId, "Maintenance required");

        assertThat(station.getStatus()).isEqualTo(StationStatus.SUSPENDED);
        verify(stationStatusHistoryService).recordTransition(
                station,
                StationStatusEventType.SUSPENDED,
                StationStatus.ACTIVE,
                admin,
                "Maintenance required"
        );
    }

    @Test
    void reactivatesSuspendedStationAndRecordsTransition() {
        UUID stationId = UUID.randomUUID();
        Station station = new Station();
        station.setStatus(StationStatus.SUSPENDED);
        UserProfile admin = UserProfile.builder().status(UserStatus.ACTIVE).build();

        when(stationRepository.findById(stationId)).thenReturn(java.util.Optional.of(station));
        when(currentProfileProvider.requireProfile()).thenReturn(admin);
        when(stationOperatingScheduleRepository.findActiveByStationId(
                eq(stationId),
                any(Instant.class)
        )).thenReturn(java.util.Optional.of(mock(StationOperatingSchedule.class)));

        stationService.reactivateStation(stationId, "Station restored");

        assertThat(station.getStatus()).isEqualTo(StationStatus.ACTIVE);
        verify(stationStatusHistoryService).recordTransition(
                station,
                StationStatusEventType.REACTIVATED,
                StationStatus.SUSPENDED,
                admin,
                "Station restored"
        );
    }

    @Test
    void ownerPausesStationAndRecordsOperationalAuditEvent() {
        UUID stationId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UserProfile owner = UserProfile.builder().status(UserStatus.ACTIVE).build();
        owner.setId(ownerId);
        Station station = Station.builder()
                .owner(owner)
                .operationalStatus(StationOperationalStatus.OPERATING)
                .build();
        station.setId(stationId);
        ChangeStationOperationalStatusRequest request =
                new ChangeStationOperationalStatusRequest(
                        StationOperationalStatus.PAUSED,
                        "  Power outage  "
                );

        when(currentProfileProvider.requireProfile()).thenReturn(owner);
        when(stationRepository.findByIdForOperationalStatusUpdate(stationId))
                .thenReturn(java.util.Optional.of(station));

        var response = stationService.changeOperationalStatusForCurrentOwner(
                stationId,
                request
        );

        assertThat(response.stationId()).isEqualTo(stationId);
        assertThat(response.operationalStatus()).isEqualTo(StationOperationalStatus.PAUSED);
        assertThat(response.reason()).isEqualTo("Power outage");
        assertThat(station.getOperationalStatusReason()).isEqualTo("Power outage");
        verify(operationalStatusPolicy).requireCanChange(
                eq(station),
                eq(StationOperationalStatus.PAUSED),
                eq("  Power outage  "),
                any(Instant.class)
        );

        ArgumentCaptor<StationOperationalStatusEvent> eventCaptor =
                ArgumentCaptor.forClass(StationOperationalStatusEvent.class);
        verify(operationalStatusEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getFromStatus())
                .isEqualTo(StationOperationalStatus.OPERATING);
        assertThat(eventCaptor.getValue().getToStatus())
                .isEqualTo(StationOperationalStatus.PAUSED);
        assertThat(eventCaptor.getValue().getReason()).isEqualTo("Power outage");
    }

    @Test
    void rejectsOperationalChangeForAnotherOwnersStation() {
        UUID stationId = UUID.randomUUID();
        UserProfile currentOwner = UserProfile.builder().build();
        currentOwner.setId(UUID.randomUUID());
        UserProfile stationOwner = UserProfile.builder().build();
        stationOwner.setId(UUID.randomUUID());
        Station station = Station.builder().owner(stationOwner).build();

        when(currentProfileProvider.requireProfile()).thenReturn(currentOwner);
        when(stationRepository.findByIdForOperationalStatusUpdate(stationId))
                .thenReturn(java.util.Optional.of(station));

        assertThatThrownBy(() -> stationService.changeOperationalStatusForCurrentOwner(
                stationId,
                new ChangeStationOperationalStatusRequest(
                        StationOperationalStatus.PAUSED,
                        "Power outage"
                )
        )).isInstanceOfSatisfying(
                AppException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(StationErrorCode.STATION_ACCESS_DENIED)
        );

        verifyNoInteractions(operationalStatusPolicy, operationalStatusEventRepository);
    }

    @Test
    void rejectsReactivationWhenOperatingScheduleIsMissing() {
        UUID stationId = UUID.randomUUID();
        Station station = new Station();
        station.setStatus(StationStatus.SUSPENDED);
        UserProfile admin = UserProfile.builder().status(UserStatus.ACTIVE).build();

        when(stationRepository.findById(stationId))
                .thenReturn(java.util.Optional.of(station));
        when(currentProfileProvider.requireProfile()).thenReturn(admin);
        when(stationOperatingScheduleRepository.findActiveByStationId(
                eq(stationId),
                any(Instant.class)
        )).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> stationService.reactivateStation(
                stationId,
                "Station restored"
        )).isInstanceOfSatisfying(
                AppException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(StationErrorCode.STATION_OPERATING_SCHEDULE_REQUIRED)
        );

        assertThat(station.getStatus()).isEqualTo(StationStatus.SUSPENDED);
        verifyNoInteractions(stationStatusHistoryService);
    }
    @Test
    void rejectsPageNumberBelowOneBeforeQuerying() {
        assertThatThrownBy(() -> stationService.getAdminStations(0, 8, new StationFilter()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(StationErrorMessage.PAGE_NUMBER_MIN.defaultMessage());

        verifyNoInteractions(stationRepository);
    }

    @Test
    void rejectsPageSizeBelowOneBeforeQuerying() {
        assertThatThrownBy(() -> stationService.getAdminStations(1, 0, new StationFilter()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(StationErrorMessage.PAGE_SIZE_MIN.defaultMessage());

        verifyNoInteractions(stationRepository);
    }

    @Test
    void rejectsPageSizeAboveMaximumBeforeQuerying() {
        assertThatThrownBy(() -> stationService.getAdminStations(1, 101, new StationFilter()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(StationErrorMessage.PAGE_SIZE_MAX.defaultMessage());

        verifyNoInteractions(stationRepository);
    }

    @Test
    void returnsAdminStationDetailWithActiveSchedulePeriods() {
        UUID stationId = UUID.randomUUID();
        Station station = new Station();
        station.setId(stationId);

        UUID scheduleId = UUID.randomUUID();
        com.thang.chargeops.station.entity.StationOperatingSchedule schedule =
                com.thang.chargeops.station.entity.StationOperatingSchedule.builder()
                        .station(station)
                        .effectiveFrom(Instant.now().minusSeconds(3600))
                        .open24Hours(false)
                        .build();
        schedule.setId(scheduleId);

        com.thang.chargeops.station.entity.StationOperatingPeriod period =
                com.thang.chargeops.station.entity.StationOperatingPeriod.builder()
                        .schedule(schedule)
                        .dayOfWeek(com.thang.chargeops.common.enums.StationDayOfWeek.MONDAY)
                        .openTime(java.time.LocalTime.of(8, 0))
                        .closeTime(java.time.LocalTime.of(22, 0))
                        .enabled(true)
                        .build();

        com.thang.chargeops.station.entity.StationAsset asset = mock(com.thang.chargeops.station.entity.StationAsset.class);
        AdminStationDetailResponse expected = mock(AdminStationDetailResponse.class);

        when(stationRepository.findAdminDetailById(stationId)).thenReturn(java.util.Optional.of(station));
        when(licenseRepository.findActiveByStationId(eq(stationId), any(Instant.class))).thenReturn(java.util.Optional.empty());
        when(stationAssetRepository.findByStationIdOrderByDisplayOrderAsc(stationId)).thenReturn(List.of(asset));
        when(stationOperatingScheduleRepository.findActiveByStationId(eq(stationId), any(Instant.class))).thenReturn(java.util.Optional.of(schedule));
        when(stationOperatingPeriodRepository.findByScheduleIdOrderByDayOfWeekAscOpenTimeAsc(scheduleId)).thenReturn(List.of(period));
        when(stationMapper.toAdminStationDetailResponse(station, List.of(period), null)).thenReturn(expected);

        AdminStationDetailResponse actual = stationService.getAdminStationDetail(stationId);

        assertThat(actual).isSameAs(expected);
        assertThat(station.getAssets()).containsExactly(asset);
        verify(stationRepository).findAdminDetailById(stationId);
        verify(stationOperatingScheduleRepository).findActiveByStationId(eq(stationId), any(Instant.class));
        verify(stationOperatingPeriodRepository).findByScheduleIdOrderByDayOfWeekAscOpenTimeAsc(scheduleId);
        verify(stationMapper).toAdminStationDetailResponse(station, List.of(period), null);
    }

}
