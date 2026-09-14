package com.thang.chargeops.station.service;

import com.thang.chargeops.common.constant.SystemConstant;
import com.thang.chargeops.common.enums.TouRateDayType;
import com.thang.chargeops.common.enums.TouRatePeriodCode;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.BookingErrorCode;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.entity.StationBookingSetting;
import com.thang.chargeops.station.entity.TouRate;
import com.thang.chargeops.station.repository.StationBookingSettingsRepository;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.station.repository.TouRateRepository;
import com.thang.chargeops.station.service.impl.StationPricingServiceImpl;
import com.thang.chargeops.station.service.model.StationPriceRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StationPricingServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-30T03:00:00Z");

    @Mock
    private StationRepository stationRepository;
    @Mock
    private StationBookingSettingsRepository settingsRepository;
    @Mock
    private TouRateRepository touRateRepository;

    private StationPricingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StationPricingServiceImpl(
                stationRepository,
                settingsRepository,
                touRateRepository
        );
    }

    @Test
    void returnsOneBasePriceRangeWhenNoTouRuleApplies() {
        UUID stationId = UUID.randomUUID();
        Station station = new Station();
        StationBookingSetting settings = StationBookingSetting.builder()
                .station(station)
                .basePriceVnd(new BigDecimal("3400.00"))
                .build();
        Instant rangeStart = localInstant(
                LocalDate.of(2026, 9, 1),
                LocalTime.MIDNIGHT
        );
        Instant rangeEnd = localInstant(
                LocalDate.of(2026, 9, 2),
                LocalTime.MIDNIGHT
        );

        when(stationRepository.findById(stationId))
                .thenReturn(Optional.of(station));
        when(settingsRepository.findByStationId(stationId))
                .thenReturn(Optional.of(settings));
        when(touRateRepository.findActiveByStationId(stationId, NOW))
                .thenReturn(List.of());

        List<StationPriceRange> result = service.resolvePriceRanges(
                stationId,
                NOW,
                rangeStart,
                rangeEnd
        );

        assertThat(result).singleElement().satisfies(range -> {
            assertThat(range.startAt()).isEqualTo(rangeStart);
            assertThat(range.endAt()).isEqualTo(rangeEnd);
            assertThat(range.rateVndPerKwh())
                    .isEqualByComparingTo(
                            StationBookingSetting.DEFAULT_BASE_PRICE_VND
                    );
            assertThat(range.periodCode())
                    .isEqualTo(TouRatePeriodCode.NORMAL);
        });
    }

    @Test
    void splitsBaseAndTouPriceRangesAtExactBoundaries() {
        UUID stationId = UUID.randomUUID();
        Station station = new Station();
        StationBookingSetting settings = StationBookingSetting.builder()
                .station(station)
                .basePriceVnd(new BigDecimal("3400.00"))
                .build();
        TouRate peak = TouRate.create(
                station,
                "Evening peak",
                TouRatePeriodCode.PEAK,
                TouRateDayType.DAILY,
                LocalTime.of(17, 0),
                LocalTime.of(21, 0),
                new BigDecimal("4200.00"),
                NOW.minusSeconds(3600)
        );
        LocalDate date = LocalDate.of(2026, 9, 1);
        Instant rangeStart = localInstant(date, LocalTime.MIDNIGHT);
        Instant rangeEnd = localInstant(
                date.plusDays(1),
                LocalTime.MIDNIGHT
        );

        when(stationRepository.findById(stationId))
                .thenReturn(Optional.of(station));
        when(settingsRepository.findByStationId(stationId))
                .thenReturn(Optional.of(settings));
        when(touRateRepository.findActiveByStationId(stationId, NOW))
                .thenReturn(List.of(peak));

        List<StationPriceRange> result = service.resolvePriceRanges(
                stationId,
                NOW,
                rangeStart,
                rangeEnd
        );

        assertThat(result).hasSize(3);
        assertThat(result.get(0).endAt())
                .isEqualTo(localInstant(date, LocalTime.of(17, 0)));
        assertThat(result.get(0).rateVndPerKwh())
                .isEqualByComparingTo("3400.00");
        assertThat(result.get(1).startAt())
                .isEqualTo(localInstant(date, LocalTime.of(17, 0)));
        assertThat(result.get(1).endAt())
                .isEqualTo(localInstant(date, LocalTime.of(21, 0)));
        assertThat(result.get(1).rateVndPerKwh())
                .isEqualByComparingTo("4200.00");
        assertThat(result.get(1).periodCode())
                .isEqualTo(TouRatePeriodCode.PEAK);
        assertThat(result.get(2).startAt())
                .isEqualTo(localInstant(date, LocalTime.of(21, 0)));
        assertThat(result.get(2).rateVndPerKwh())
                .isEqualByComparingTo("3400.00");
    }

    @Test
    void throwsConflictWhenStationPricingNotConfigured() {
        UUID stationId = UUID.randomUUID();
        Station station = new Station();
        Instant targetTime = NOW;
        Instant rangeStart = localInstant(LocalDate.of(2026, 9, 1), LocalTime.MIDNIGHT);
        Instant rangeEnd = localInstant(LocalDate.of(2026, 9, 2), LocalTime.MIDNIGHT);

        when(stationRepository.findById(stationId)).thenReturn(Optional.of(station));
        when(settingsRepository.findByStationId(stationId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolvePriceAt(stationId, targetTime))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(BookingErrorCode.PRICING_NOT_CONFIGURED)
                );

        assertThatThrownBy(() -> service.resolvePriceRanges(stationId, NOW, rangeStart, rangeEnd))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(BookingErrorCode.PRICING_NOT_CONFIGURED)
                );
    }

    @Test
    void throwsConflictWhenBasePriceIsZeroOrNegative() {
        UUID stationId = UUID.randomUUID();
        Station station = new Station();
        StationBookingSetting zeroPriceSettings = StationBookingSetting.builder()
                .station(station)
                .basePriceVnd(BigDecimal.ZERO)
                .build();
        Instant targetTime = NOW;
        Instant rangeStart = localInstant(LocalDate.of(2026, 9, 1), LocalTime.MIDNIGHT);
        Instant rangeEnd = localInstant(LocalDate.of(2026, 9, 2), LocalTime.MIDNIGHT);

        when(stationRepository.findById(stationId)).thenReturn(Optional.of(station));
        when(settingsRepository.findByStationId(stationId)).thenReturn(Optional.of(zeroPriceSettings));

        assertThatThrownBy(() -> service.resolvePriceAt(stationId, targetTime))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(BookingErrorCode.PRICING_NOT_CONFIGURED)
                );

        assertThatThrownBy(() -> service.resolvePriceRanges(stationId, NOW, rangeStart, rangeEnd))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(BookingErrorCode.PRICING_NOT_CONFIGURED)
                );
    }

    private Instant localInstant(LocalDate date, LocalTime time) {
        return date.atTime(time)
                .atZone(ZoneId.of(SystemConstant.SYSTEM_REGION_TIMEZONE))
                .toInstant();
    }
}
