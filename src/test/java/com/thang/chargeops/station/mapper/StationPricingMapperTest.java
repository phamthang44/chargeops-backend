package com.thang.chargeops.station.mapper;

import com.thang.chargeops.common.enums.StationDayOfWeek;
import com.thang.chargeops.station.dto.station.response.StationPricingResponse;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.entity.StationBookingSetting;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StationPricingMapperTest {

    private final StationPricingMapper mapper = new StationPricingMapper();

    @Test
    void mapsSystemDurationPolicyAndDefaultOperatingWeek() {
        UUID stationId = UUID.randomUUID();
        StationBookingSetting settings = StationBookingSetting.builder()
                .station(new Station())
                .minDurationMinutes(60)
                .durationStepMinutes(45)
                .maxDurationMinutes(240)
                .basePriceVnd(new BigDecimal("3900.00"))
                .build();

        StationPricingResponse response =
                mapper.toResponse(stationId, settings, null, List.of());

        assertThat(response.stationId()).isEqualTo(stationId);
        assertThat(response.durationStepMinutes()).isEqualTo(30);
        assertThat(response.maxDurationMinutes()).isEqualTo(180);
        assertThat(response.hours()).hasSize(StationDayOfWeek.values().length)
                .allSatisfy(hour -> {
                    assertThat(hour.enabled()).isTrue();
                    assertThat(hour.openTime()).isEqualTo(LocalTime.of(6, 0));
                    assertThat(hour.closeTime()).isEqualTo(LocalTime.of(23, 0));
                });
    }
}
