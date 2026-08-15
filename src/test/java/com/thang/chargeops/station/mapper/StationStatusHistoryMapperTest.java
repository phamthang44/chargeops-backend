package com.thang.chargeops.station.mapper;

import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.common.enums.StationStatusEventType;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.station.dto.station.response.StationStatusHistoryResponse;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.entity.StationStatusHistory;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StationStatusHistoryMapperTest {

    private final StationStatusHistoryMapper mapper = Mappers.getMapper(StationStatusHistoryMapper.class);

    @Test
    void flattensStationAndPerformerFields() {
        UUID historyId = UUID.randomUUID();
        UUID stationId = UUID.randomUUID();
        UUID performerId = UUID.randomUUID();
        Instant performedAt = Instant.parse("2026-08-15T03:00:00Z");

        Station station = new Station();
        station.setId(stationId);
        station.setStationCode("ST-1044");
        station.setName("Trạm Sạc Tây Hồ");

        UserProfile performer = new UserProfile();
        performer.setId(performerId);
        performer.setDisplayName(" ");
        performer.setEmail("owner@example.com");

        StationStatusHistory history = StationStatusHistory.builder()
                .id(historyId)
                .station(station)
                .stationStatusEventType(StationStatusEventType.SUBMITTED)
                .fromStatus(null)
                .toStatus(StationStatus.PENDING_APPROVAL)
                .performedBy(performer)
                .performedAt(performedAt)
                .build();

        StationStatusHistoryResponse response = mapper.toStationStatusHistoryResponse(history);

        assertThat(response.id()).isEqualTo(historyId);
        assertThat(response.stationId()).isEqualTo(stationId);
        assertThat(response.stationCode()).isEqualTo("ST-1044");
        assertThat(response.stationName()).isEqualTo("Trạm Sạc Tây Hồ");
        assertThat(response.eventType()).isEqualTo(StationStatusEventType.SUBMITTED);
        assertThat(response.performedById()).isEqualTo(performerId);
        assertThat(response.performedByName()).isEqualTo("owner@example.com");
        assertThat(response.performedByEmail()).isEqualTo("owner@example.com");
        assertThat(response.performedByRole()).isEqualTo("STATION_OWNER");
        assertThat(response.performedAt()).isEqualTo(performedAt);
    }

    @Test
    void mapsAdministrativeEventsToAdminRole() {
        assertThat(mapper.resolvePerformedByRole(StationStatusEventType.APPROVED)).isEqualTo("ADMIN");
        assertThat(mapper.resolvePerformedByRole(StationStatusEventType.REJECTED)).isEqualTo("ADMIN");
        assertThat(mapper.resolvePerformedByRole(StationStatusEventType.SUSPENDED)).isEqualTo("ADMIN");
        assertThat(mapper.resolvePerformedByRole(StationStatusEventType.REACTIVATED)).isEqualTo("ADMIN");
    }
}
