package com.thang.chargeops.station.service;

import com.thang.chargeops.common.enums.StationDayOfWeek;
import com.thang.chargeops.common.enums.TouRateDayType;
import com.thang.chargeops.common.enums.TouRatePeriodCode;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.service.UserProfileService;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.dto.station.request.UpdateStationPricingRequest;
import com.thang.chargeops.station.dto.station.response.StationPricingResponse;
import com.thang.chargeops.station.dto.station.response.StationScheduleHistoryResponse;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.entity.StationBookingSetting;
import com.thang.chargeops.station.entity.StationOperatingSchedule;
import com.thang.chargeops.station.entity.TouRate;
import com.thang.chargeops.station.exception.StationPricingDomainException;
import com.thang.chargeops.station.exception.violation.StationPricingViolation;
import com.thang.chargeops.station.mapper.StationPricingMapper;
import com.thang.chargeops.station.policy.StationPricingPolicy;
import com.thang.chargeops.station.repository.StationBookingSettingsRepository;
import com.thang.chargeops.station.repository.StationOperatingScheduleRepository;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.repository.TouRateRepository;
import com.thang.chargeops.station.service.impl.StationConfigurationServiceImpl;
import com.thang.chargeops.station.service.support.StationPricingExceptionTranslator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StationConfigurationServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-30T03:00:00Z");

    @Mock
    private StationRepository stationRepository;
    @Mock
    private StationBookingSettingsRepository settingsRepository;
    @Mock
    private StationOperatingScheduleRepository scheduleRepository;
    @Mock
    private TouRateRepository touRateRepository;
    @Mock
    private CurrentProfileProvider currentProfileProvider;
    @Mock
    private StationPricingPolicy pricingPolicy;
    @Mock
    private UserProfileService userProfileService;
    @Mock
    private EntityManager entityManager;

    private StationConfigurationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StationConfigurationServiceImpl(
                stationRepository,
                settingsRepository,
                scheduleRepository,
                touRateRepository,
                currentProfileProvider,
                pricingPolicy,
                new StationPricingMapper(),
                new StationPricingExceptionTranslator(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                userProfileService,
                entityManager
        );
    }

    @Test
    void updatesOneTemporalConfigurationSnapshotAtTheClockInstant() {
        UUID stationId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Station station = new Station();
        station.setId(stationId);
        Instant previousEffectiveFrom = Instant.parse("2026-08-01T00:00:00Z");
        StationOperatingSchedule previousSchedule =
                StationOperatingSchedule.create(
                        station,
                        true,
                        previousEffectiveFrom
                );
        TouRate previousRate = TouRate.create(
                station,
                "Old peak",
                TouRatePeriodCode.PEAK,
                TouRateDayType.DAILY,
                LocalTime.of(17, 0),
                LocalTime.of(21, 0),
                new BigDecimal("4200.00"),
                previousEffectiveFrom
        );
        UpdateStationPricingRequest request = new UpdateStationPricingRequest(
                60,
                new BigDecimal("3600.00"),
                false,
                Arrays.stream(StationDayOfWeek.values())
                        .map(day -> new UpdateStationPricingRequest
                                .OperatingHourRequest(
                                        day,
                                        LocalTime.of(6, 0),
                                        LocalTime.of(23, 0),
                                        true
                                ))
                        .toList(),
                List.of(new UpdateStationPricingRequest.TouRuleRequest(
                        null,
                        " New off peak ",
                        TouRatePeriodCode.OFF_PEAK,
                        TouRateDayType.DAILY,
                        LocalTime.of(23, 0),
                        LocalTime.of(5, 0),
                        new BigDecimal("2800.00")
                )),
                0L
        );

        when(stationRepository.findByIdForPricingUpdate(stationId))
                .thenReturn(Optional.of(station));
        when(currentProfileProvider.requireProfileId()).thenReturn(ownerId);
        when(settingsRepository.findByStationId(stationId))
                .thenReturn(Optional.empty());
        AtomicReference<StationOperatingSchedule> persistedCurrent = new AtomicReference<>();
        when(scheduleRepository.findActiveByStationId(stationId, NOW))
                .thenAnswer(invocation -> Optional.ofNullable(
                        persistedCurrent.get() != null
                                ? persistedCurrent.get()
                                : previousSchedule
                ));
        when(touRateRepository.findActiveByStationId(stationId, NOW))
                .thenReturn(List.of(previousRate));
        when(scheduleRepository.saveAndFlush(any(StationOperatingSchedule.class)))
                .thenAnswer(invocation -> {
                    StationOperatingSchedule saved = invocation.getArgument(0);
                    saved.setId(UUID.randomUUID());
                    saved.setCreatedAt(NOW);
                    persistedCurrent.set(saved);
                    return saved;
                });

        StationPricingResponse response = service.updateConfiguration(
                stationId,
                request
        );

        ArgumentCaptor<StationBookingSetting> settingsCaptor =
                ArgumentCaptor.forClass(StationBookingSetting.class);
        ArgumentCaptor<StationOperatingSchedule> scheduleCaptor =
                ArgumentCaptor.forClass(StationOperatingSchedule.class);
        verify(settingsRepository).save(settingsCaptor.capture());
        verify(scheduleRepository).saveAndFlush(scheduleCaptor.capture());
        verify(scheduleRepository).flush();
        verify(pricingPolicy).validateStationOwnershipAndStatus(
                station,
                ownerId
        );

        assertThat(settingsCaptor.getValue().getMinDurationMinutes())
                .isEqualTo(60);
        assertThat(settingsCaptor.getValue().getBasePriceVnd())
                .isEqualByComparingTo("3600.00");
        assertThat(previousSchedule.getEffectiveTo()).isEqualTo(NOW);
        assertThat(previousRate.getEffectiveTo()).isEqualTo(NOW);
        assertThat(scheduleCaptor.getValue().getEffectiveFrom()).isEqualTo(NOW);
        assertThat(scheduleCaptor.getValue().getPeriods())
                .hasSize(StationDayOfWeek.values().length);
        assertThat(response.scheduleEffectiveFrom()).isEqualTo(NOW);
        assertThat(response.scheduleEffectiveTo()).isNull();
        assertThat(response.scheduleStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void defersAnExistingCommercialEditUntilNextMondayAndReturnsItAsPending() {
        UUID stationId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Station station = new Station();
        station.setId(stationId);
        StationBookingSetting settings = StationBookingSetting.builder()
                .station(station)
                .minDurationMinutes(30)
                .basePriceVnd(new BigDecimal("3200.00"))
                .version(0L)
                .build();
        StationOperatingSchedule active = StationOperatingSchedule.create(
                station,
                true,
                Instant.parse("2026-08-23T17:00:00Z"),
                30,
                new BigDecimal("3200.00")
        );
        active.setId(UUID.randomUUID());
        AtomicReference<StationOperatingSchedule> pendingRef = new AtomicReference<>();
        Instant expectedBoundary = Instant.parse("2026-08-30T17:00:00Z");
        UpdateStationPricingRequest request = new UpdateStationPricingRequest(
                60,
                new BigDecimal("3600.00"),
                false,
                Arrays.stream(StationDayOfWeek.values())
                        .map(day -> new UpdateStationPricingRequest.OperatingHourRequest(
                                day,
                                LocalTime.of(6, 0),
                                LocalTime.of(23, 0),
                                true
                        ))
                        .toList(),
                List.of(),
                1L
        );

        when(stationRepository.findByIdForPricingUpdate(stationId))
                .thenReturn(Optional.of(station));
        when(currentProfileProvider.requireProfileId()).thenReturn(ownerId);
        when(settingsRepository.findByStationId(stationId))
                .thenReturn(Optional.of(settings));
        when(scheduleRepository.findActiveByStationId(stationId, NOW))
                .thenReturn(Optional.of(active));
        when(scheduleRepository.findPendingByStationId(stationId, NOW))
                .thenAnswer(invocation -> pendingRef.get() == null
                        ? List.of()
                        : List.of(pendingRef.get()));
        when(touRateRepository.findByScheduleIdOrderByDayTypeAscStartTimeAsc(any(UUID.class)))
                .thenReturn(List.of());
        when(touRateRepository.findActiveByStationId(stationId, NOW))
                .thenReturn(List.of());
        when(scheduleRepository.saveAndFlush(any(StationOperatingSchedule.class)))
                .thenAnswer(invocation -> {
                    StationOperatingSchedule saved = invocation.getArgument(0);
                    saved.setId(UUID.randomUUID());
                    saved.setCreatedAt(NOW);
                    pendingRef.set(saved);
                    return saved;
                });

        StationPricingResponse response = service.updateConfiguration(stationId, request);

        assertThat(active.getEffectiveTo()).isEqualTo(expectedBoundary);
        assertThat(pendingRef.get().getEffectiveFrom()).isEqualTo(expectedBoundary);
        assertThat(pendingRef.get().getEffectiveTo()).isNull();
        assertThat(response.scheduleEffectiveFrom()).isEqualTo(active.getEffectiveFrom());
        assertThat(response.scheduleEffectiveTo()).isEqualTo(expectedBoundary);
        assertThat(response.basePriceVnd()).isEqualByComparingTo("3200.00");
        assertThat(response.pendingConfiguration()).isNotNull();
        assertThat(response.pendingConfiguration().effectiveFrom()).isEqualTo(expectedBoundary);
        assertThat(response.pendingConfiguration().changedAt()).isEqualTo(NOW);
        assertThat(response.pendingConfiguration().status()).isEqualTo("PENDING");
        assertThat(response.pendingConfiguration().basePriceVnd())
                .isEqualByComparingTo("3600.00");
        assertThat(response.pendingConfiguration().minBookingDurationMin()).isEqualTo(60);
    }

    @Test
    void restoringCurrentVersionCancelsPendingAndRestoresLegacySettingsValues() {
        UUID stationId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Station station = new Station();
        station.setId(stationId);
        StationBookingSetting settings = StationBookingSetting.builder()
                .station(station)
                .minDurationMinutes(60)
                .basePriceVnd(new BigDecimal("3600.00"))
                .version(1L)
                .build();
        Instant activeFrom = Instant.parse("2026-08-23T17:00:00Z");
        Instant boundary = Instant.parse("2026-08-30T17:00:00Z");
        StationOperatingSchedule active = StationOperatingSchedule.create(
                station,
                true,
                activeFrom,
                30,
                new BigDecimal("3200.00")
        );
        active.setId(UUID.randomUUID());
        active.setEffectiveTo(boundary);
        StationOperatingSchedule pending = StationOperatingSchedule.create(
                station,
                true,
                boundary,
                60,
                new BigDecimal("3600.00")
        );
        pending.setId(UUID.randomUUID());
        UpdateStationPricingRequest request = new UpdateStationPricingRequest(
                30,
                new BigDecimal("3200.00"),
                true,
                List.of(),
                List.of(),
                2L
        );

        when(stationRepository.findByIdForPricingUpdate(stationId))
                .thenReturn(Optional.of(station));
        when(currentProfileProvider.requireProfileId()).thenReturn(ownerId);
        when(settingsRepository.findByStationId(stationId))
                .thenReturn(Optional.of(settings));
        when(scheduleRepository.findActiveByStationId(stationId, NOW))
                .thenReturn(Optional.of(active));
        when(scheduleRepository.findPendingByStationId(stationId, NOW))
                .thenAnswer(invocation -> pending.getCancelledAt() == null
                        ? List.of(pending)
                        : List.of());
        when(touRateRepository.findByScheduleIdOrderByDayTypeAscStartTimeAsc(active.getId()))
                .thenReturn(List.of());
        when(touRateRepository.findByScheduleIdOrderByDayTypeAscStartTimeAsc(pending.getId()))
                .thenReturn(List.of());

        StationPricingResponse response = service.updateConfiguration(stationId, request);

        assertThat(pending.getCancelledAt()).isEqualTo(NOW);
        assertThat(pending.statusAt(NOW)).isEqualTo("CANCELLED");
        assertThat(active.getEffectiveTo()).isNull();
        assertThat(settings.getMinDurationMinutes()).isEqualTo(30);
        assertThat(settings.getBasePriceVnd()).isEqualByComparingTo("3200.00");
        assertThat(response.pendingConfiguration()).isNull();
        assertThat(response.scheduleEffectiveTo()).isNull();
        assertThat(response.basePriceVnd()).isEqualByComparingTo("3200.00");
    }

    @Test
    void replacingPendingVersionCancelsOldEntryAndKeepsTheSameBoundary() {
        UUID stationId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Station station = new Station();
        station.setId(stationId);
        StationBookingSetting settings = StationBookingSetting.builder()
                .station(station)
                .minDurationMinutes(60)
                .basePriceVnd(new BigDecimal("3600.00"))
                .version(1L)
                .build();
        Instant boundary = Instant.parse("2026-08-30T17:00:00Z");
        StationOperatingSchedule active = StationOperatingSchedule.create(
                station,
                true,
                Instant.parse("2026-08-23T17:00:00Z"),
                30,
                new BigDecimal("3200.00")
        );
        active.setId(UUID.randomUUID());
        active.setEffectiveTo(boundary);
        StationOperatingSchedule oldPending = StationOperatingSchedule.create(
                station,
                true,
                boundary,
                60,
                new BigDecimal("3600.00")
        );
        oldPending.setId(UUID.randomUUID());
        AtomicReference<StationOperatingSchedule> pendingRef =
                new AtomicReference<>(oldPending);
        UpdateStationPricingRequest request = new UpdateStationPricingRequest(
                60,
                new BigDecimal("3900.00"),
                true,
                List.of(),
                List.of(),
                2L
        );

        when(stationRepository.findByIdForPricingUpdate(stationId))
                .thenReturn(Optional.of(station));
        when(currentProfileProvider.requireProfileId()).thenReturn(ownerId);
        when(settingsRepository.findByStationId(stationId))
                .thenReturn(Optional.of(settings));
        when(scheduleRepository.findActiveByStationId(stationId, NOW))
                .thenReturn(Optional.of(active));
        when(scheduleRepository.findPendingByStationId(stationId, NOW))
                .thenAnswer(invocation -> pendingRef.get().getCancelledAt() == null
                        ? List.of(pendingRef.get())
                        : List.of());
        when(touRateRepository.findByScheduleIdOrderByDayTypeAscStartTimeAsc(any(UUID.class)))
                .thenReturn(List.of());
        when(touRateRepository.findActiveByStationId(stationId, NOW))
                .thenReturn(List.of());
        when(scheduleRepository.saveAndFlush(any(StationOperatingSchedule.class)))
                .thenAnswer(invocation -> {
                    StationOperatingSchedule saved = invocation.getArgument(0);
                    saved.setId(UUID.randomUUID());
                    saved.setCreatedAt(NOW);
                    pendingRef.set(saved);
                    return saved;
                });

        StationPricingResponse response = service.updateConfiguration(stationId, request);

        assertThat(oldPending.getCancelledAt()).isEqualTo(NOW);
        assertThat(oldPending.statusAt(NOW)).isEqualTo("CANCELLED");
        assertThat(active.getEffectiveTo()).isEqualTo(boundary);
        assertThat(pendingRef.get()).isNotSameAs(oldPending);
        assertThat(pendingRef.get().getEffectiveFrom()).isEqualTo(boundary);
        assertThat(response.pendingConfiguration().scheduleId()).isEqualTo(pendingRef.get().getId());
        assertThat(response.pendingConfiguration().basePriceVnd())
                .isEqualByComparingTo("3900.00");
    }

    @Test
    void translatesPricingDomainViolationAtTheApplicationBoundary() {
        UUID stationId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Station station = new Station();
        UpdateStationPricingRequest request = new UpdateStationPricingRequest(
                60,
                BigDecimal.ONE,
                true,
                List.of(),
                List.of(),
                0L
        );

        when(stationRepository.findByIdForPricingUpdate(stationId))
                .thenReturn(Optional.of(station));
        when(currentProfileProvider.requireProfileId()).thenReturn(ownerId);
        doThrow(new StationPricingDomainException(
                StationPricingViolation.MIN_BOOKING_DURATION_INVALID,
                "Minimum booking duration is invalid"
        )).when(pricingPolicy).validateBookingSettings(60);

        assertThatThrownBy(() -> service.updateConfiguration(stationId, request))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(
                                        StationErrorCode
                                                .PRICING_MIN_BOOKING_DURATION_INVALID
                                )
                );
    }

    @Test
    void getScheduleHistoryReturnsSchedulesWithStatusAndActor() {
        UUID stationId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        Station station = new Station();

        Instant activeFrom = NOW.minusSeconds(3600);
        StationOperatingSchedule activeSchedule =
                StationOperatingSchedule.create(station, true, activeFrom);
        activeSchedule.setId(UUID.randomUUID());
        activeSchedule.setCreatedBy(profileId);

        Instant expiredFrom = NOW.minusSeconds(86400);
        StationOperatingSchedule expiredSchedule =
                StationOperatingSchedule.create(station, false, expiredFrom);
        expiredSchedule.setId(UUID.randomUUID());
        expiredSchedule.setEffectiveTo(activeFrom);
        expiredSchedule.setCreatedBy(profileId);

        UserProfile profile = UserProfile.builder()
                .displayName("Station Owner")
                .email("owner@chargeops.vn")
                .build();

        when(stationRepository.findById(stationId))
                .thenReturn(Optional.of(station));
        when(currentProfileProvider.requireProfileId()).thenReturn(ownerId);
        when(scheduleRepository.findByStationIdOrderByEffectiveFromDescCreatedAtDesc(stationId))
                .thenReturn(List.of(activeSchedule, expiredSchedule));
        when(userProfileService.getUserProfileById(profileId))
                .thenReturn(profile);

        List<StationScheduleHistoryResponse> history =
                service.getScheduleHistory(stationId);

        verify(pricingPolicy).validateStationOwnershipAndStatus(
                station,
                ownerId
        );
        assertThat(history).hasSize(2);
        assertThat(history.get(0).status()).isEqualTo("ACTIVE");
        assertThat(history.get(0).changedByName()).isEqualTo("Station Owner");
        assertThat(history.get(0).open24Hours()).isTrue();
        assertThat(history.get(1).status()).isEqualTo("EXPIRED");
        assertThat(history.get(1).effectiveTo()).isEqualTo(activeFrom);
    }

    @Test
    void throwsConflictWhenStaleVersionProvidedForExistingSettings() {
        UUID stationId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Station station = new Station();
        StationBookingSetting existing = StationBookingSetting.builder()
                .station(station)
                .minDurationMinutes(30)
                .basePriceVnd(new BigDecimal("3400.00"))
                .version(1L)
                .build();
        UpdateStationPricingRequest request = new UpdateStationPricingRequest(
                30,
                new BigDecimal("3500.00"),
                true,
                List.of(),
                List.of(),
                1L // settings.version = 1 -> commercial version = 2, so 1L is stale
        );

        when(stationRepository.findByIdForPricingUpdate(stationId))
                .thenReturn(Optional.of(station));
        when(currentProfileProvider.requireProfileId()).thenReturn(ownerId);
        when(settingsRepository.findByStationId(stationId))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.updateConfiguration(stationId, request))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(StationErrorCode.PRICING_CONFIGURATION_CONFLICT)
                );
    }

    @Test
    void throwsConflictWhenNonZeroVersionProvidedForUnconfiguredStation() {
        UUID stationId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Station station = new Station();
        UpdateStationPricingRequest request = new UpdateStationPricingRequest(
                30,
                new BigDecimal("3500.00"),
                true,
                List.of(),
                List.of(),
                5L // Unconfigured station expects version 0L
        );

        when(stationRepository.findByIdForPricingUpdate(stationId))
                .thenReturn(Optional.of(station));
        when(currentProfileProvider.requireProfileId()).thenReturn(ownerId);
        when(settingsRepository.findByStationId(stationId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateConfiguration(stationId, request))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(StationErrorCode.PRICING_CONFIGURATION_CONFLICT)
                );
    }

    @Test
    void forcesOptimisticLockIncrementWhenUpdatingExistingSettings() {
        UUID stationId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Station station = new Station();
        station.setId(stationId);
        StationBookingSetting existing = StationBookingSetting.builder()
                .station(station)
                .minDurationMinutes(30)
                .basePriceVnd(new BigDecimal("3400.00"))
                .version(0L)
                .build();
        UpdateStationPricingRequest request = new UpdateStationPricingRequest(
                60,
                new BigDecimal("3600.00"),
                true,
                List.of(),
                List.of(),
                1L // JPA version = 0 -> commercial version = 1
        );

        when(stationRepository.findByIdForPricingUpdate(stationId))
                .thenReturn(Optional.of(station));
        when(currentProfileProvider.requireProfileId()).thenReturn(ownerId);
        when(settingsRepository.findByStationId(stationId))
                .thenReturn(Optional.of(existing));
        AtomicReference<StationOperatingSchedule> persistedCurrent = new AtomicReference<>();
        when(scheduleRepository.findActiveByStationId(stationId, NOW))
                .thenAnswer(invocation -> Optional.ofNullable(persistedCurrent.get()));
        when(scheduleRepository.saveAndFlush(any(StationOperatingSchedule.class)))
                .thenAnswer(invocation -> {
                    StationOperatingSchedule saved = invocation.getArgument(0);
                    saved.setId(UUID.randomUUID());
                    saved.setCreatedAt(NOW);
                    persistedCurrent.set(saved);
                    return saved;
                });
        when(touRateRepository.findActiveByStationId(stationId, NOW))
                .thenReturn(List.of());

        StationPricingResponse response = service.updateConfiguration(stationId, request);

        verify(entityManager).lock(existing, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        assertThat(response.version()).isEqualTo(1L); // 0 + 1
        assertThat(response.scheduleStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void getConfigurationReturnsUnconfiguredStatusWhenSettingsDoNotExist() {
        UUID stationId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Station station = new Station();
        station.setId(stationId);

        when(stationRepository.findById(stationId))
                .thenReturn(Optional.of(station));
        when(currentProfileProvider.requireProfileId()).thenReturn(ownerId);
        when(settingsRepository.findByStationId(stationId))
                .thenReturn(Optional.empty());
        when(scheduleRepository.findActiveByStationId(stationId, NOW))
                .thenReturn(Optional.empty());
        StationPricingResponse response = service.getConfiguration(stationId);

        assertThat(response.scheduleStatus()).isEqualTo("UNCONFIGURED");
        assertThat(response.version()).isEqualTo(0L);
        assertThat(response.basePriceVnd()).isNull();
    }
}
