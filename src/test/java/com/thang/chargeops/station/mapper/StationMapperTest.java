package com.thang.chargeops.station.mapper;

import com.thang.chargeops.common.enums.Plan;
import com.thang.chargeops.common.enums.StationOperatingState;
import com.thang.chargeops.common.enums.StationOperationalStatus;
import com.thang.chargeops.station.dto.station.response.OwnerStationSummaryResponse;
import com.thang.chargeops.station.projection.OwnerStationSummaryProjection;
import com.thang.chargeops.station.service.support.StationOperatingStatus;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StationMapperTest {

    private final StationMapper mapper = Mappers.getMapper(StationMapper.class);

    @Test
    void mapsActiveLicenseFieldsToNestedSummary() {
        OwnerStationSummaryProjection projection = mock(OwnerStationSummaryProjection.class);
        Instant expiresAt = Instant.parse("2027-08-15T08:30:00Z");
        when(projection.getLicensePlan()).thenReturn(Plan.YEARLY);
        when(projection.getLicenseExpiresAt()).thenReturn(expiresAt);
        when(projection.getOperationalStatus()).thenReturn(StationOperationalStatus.OPERATING);

        OwnerStationSummaryResponse response = mapper.toOwnerStationSummaryResponse(
                projection,
                StationOperatingStatus.open()
        );

        assertThat(response.licenseSummary()).isNotNull();
        assertThat(response.licenseSummary().plan()).isEqualTo(Plan.YEARLY);
        assertThat(response.licenseSummary().expiresAt()).isEqualTo(expiresAt);
        assertThat(response.operationalStatus()).isEqualTo(StationOperationalStatus.OPERATING);
        assertThat(response.openNow()).isTrue();
        assertThat(response.operatingState()).isEqualTo(StationOperatingState.OPEN);
        assertThat(response.scheduleConfigured()).isTrue();
    }

    @Test
    void mapsMissingActiveLicenseToNullSummary() {
        OwnerStationSummaryProjection projection = mock(OwnerStationSummaryProjection.class);

        OwnerStationSummaryResponse response = mapper.toOwnerStationSummaryResponse(
                projection,
                StationOperatingStatus.scheduleNotConfigured()
        );

        assertThat(response.licenseSummary()).isNull();
        assertThat(response.openNow()).isFalse();
        assertThat(response.operatingState())
                .isEqualTo(StationOperatingState.SCHEDULE_NOT_CONFIGURED);
    }
}
