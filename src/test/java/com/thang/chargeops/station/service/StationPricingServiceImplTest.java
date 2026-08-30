package com.thang.chargeops.station.service;

import com.thang.chargeops.common.enums.StationDayOfWeek;
import com.thang.chargeops.common.enums.TouRateDayType;
import com.thang.chargeops.common.enums.TouRatePeriodCode;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.StationErrorCode;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.dto.station.request.UpdateStationPricingRequest;
import com.thang.chargeops.station.dto.station.response.StationPricingResponse;
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
import com.thang.chargeops.station.service.impl.StationPricingServiceImpl;
import com.thang.chargeops.station.service.support.StationPricingExceptionTranslator;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StationPricingServiceImplTest {

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

    private StationPricingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StationPricingServiceImpl(
                stationRepository,
                settingsRepository,
                scheduleRepository,
                touRateRepository,
                currentProfileProvider,
                pricingPolicy,
                new StationPricingMapper(),
                new StationPricingExceptionTranslator(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void updatesOneTemporalPricingSnapshotAtTheClockInstant() {
        UUID stationId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Station station = new Station();
        Instant previousEffectiveFrom = Instant.parse("2026-08-01T00:00:00Z");
        StationOperatingSchedule previousSchedule =
                StationOperatingSchedule.create(station, true, previousEffectiveFrom);
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
                        .map(day -> new UpdateStationPricingRequest.OperatingHourRequest(
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
                ))
        );

        when(stationRepository.findByIdForPricingUpdate(stationId)).thenReturn(Optional.of(station));
        when(currentProfileProvider.requireProfileId()).thenReturn(ownerId);
        when(settingsRepository.findByStationId(stationId)).thenReturn(Optional.empty());
        when(scheduleRepository.findActiveByStationId(stationId, NOW))
                .thenReturn(Optional.of(previousSchedule));
        when(touRateRepository.findActiveByStationId(stationId, NOW))
                .thenReturn(List.of(previousRate));

        StationPricingResponse response = service.updateStationPricing(stationId, request);

        ArgumentCaptor<StationBookingSetting> settingsCaptor =
                ArgumentCaptor.forClass(StationBookingSetting.class);
        ArgumentCaptor<StationOperatingSchedule> scheduleCaptor =
                ArgumentCaptor.forClass(StationOperatingSchedule.class);
        verify(settingsRepository).save(settingsCaptor.capture());
        verify(scheduleRepository).save(scheduleCaptor.capture());
        verify(scheduleRepository).flush();
        verify(pricingPolicy).validateStationOwnershipAndStatus(station, ownerId);

        assertThat(settingsCaptor.getValue().getMinDurationMinutes()).isEqualTo(60);
        assertThat(settingsCaptor.getValue().getBasePriceVnd()).isEqualByComparingTo("3600.00");
        assertThat(previousSchedule.getEffectiveTo()).isEqualTo(NOW);
        assertThat(previousRate.getEffectiveTo()).isEqualTo(NOW);
        assertThat(scheduleCaptor.getValue().getEffectiveFrom()).isEqualTo(NOW);
        assertThat(scheduleCaptor.getValue().getPeriods())
                .hasSize(StationDayOfWeek.values().length);
        assertThat(response.touRules()).singleElement()
                .satisfies(rule -> {
                    assertThat(rule.name()).isEqualTo("New off peak");
                    assertThat(rule.rateVnd()).isEqualByComparingTo("2800.00");
        });
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
                List.of()
        );

        when(stationRepository.findByIdForPricingUpdate(stationId)).thenReturn(Optional.of(station));
        when(currentProfileProvider.requireProfileId()).thenReturn(ownerId);
        doThrow(new StationPricingDomainException(
                StationPricingViolation.MIN_BOOKING_DURATION_INVALID,
                "Minimum booking duration is invalid"
        )).when(pricingPolicy).validateBookingSettings(60);

        assertThatThrownBy(() -> service.updateStationPricing(stationId, request))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(StationErrorCode.PRICING_MIN_BOOKING_DURATION_INVALID)
                );
    }
}
