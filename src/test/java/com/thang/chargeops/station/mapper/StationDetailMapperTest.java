package com.thang.chargeops.station.mapper;

import com.thang.chargeops.booking.policy.model.CancellationPolicySummary;
import com.thang.chargeops.common.enums.ChargerType;
import com.thang.chargeops.common.enums.ConnectorType;
import com.thang.chargeops.common.enums.OperationalChargePointStatus;
import com.thang.chargeops.common.enums.StationDayOfWeek;
import com.thang.chargeops.common.enums.StationOperatingState;
import com.thang.chargeops.location.entity.AdministrativeProvince;
import com.thang.chargeops.location.entity.AdministrativeWard;
import com.thang.chargeops.station.dto.station.response.StationAssetResponse;
import com.thang.chargeops.station.dto.station.response.StationDiscoveryDetailResponse;
import com.thang.chargeops.station.entity.ChargePoint;
import com.thang.chargeops.station.entity.Connector;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.entity.StationAsset;
import com.thang.chargeops.station.entity.StationOperatingSchedule;
import com.thang.chargeops.station.service.support.StationOperatingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StationDetailMapperTest {

    @Mock
    private StationMapper stationMapper;

    private StationDetailMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new StationDetailMapper(stationMapper);
    }

    @Test
    void mapsStableSevenDayDetailAndCurrentEquipmentState() {
        UUID stationId = UUID.randomUUID();
        StationAsset asset = mock(StationAsset.class);
        StationAssetResponse assetResponse = new StationAssetResponse();
        assetResponse.setAssetUrl("https://cdn.test/station.jpg");
        when(stationMapper.toStationAssetResponse(asset)).thenReturn(assetResponse);

        AdministrativeProvince province = mock(AdministrativeProvince.class);
        AdministrativeWard ward = mock(AdministrativeWard.class);
        when(ward.getFullName()).thenReturn("Phường Bến Nghé");
        when(ward.getProvince()).thenReturn(province);
        when(province.getFullName()).thenReturn("Thành phố Hồ Chí Minh");

        Station station = Station.builder()
                .stationCode("ST-0001")
                .name("Central Fast Charge")
                .description("Trạm sạc trung tâm")
                .addressLine("1 Lê Lợi")
                .ward(ward)
                .latitude(new BigDecimal("10.776900"))
                .longitude(new BigDecimal("106.700900"))
                .contactPhone("0900000000")
                .operationalStatus(com.thang.chargeops.common.enums.StationOperationalStatus.OPERATING)
                .assets(List.of(asset))
                .build();
        station.setId(stationId);

        Instant effectiveFrom = Instant.parse("2026-09-01T00:00:00Z");
        StationOperatingSchedule schedule = StationOperatingSchedule.builder()
                .effectiveFrom(effectiveFrom)
                .open24Hours(false)
                .build();
        schedule.addPeriod(
                StationDayOfWeek.TUESDAY,
                LocalTime.of(8, 0),
                LocalTime.of(22, 0),
                true
        );
        schedule.addPeriod(StationDayOfWeek.WEDNESDAY, null, null, false);

        ChargePoint chargePoint = ChargePoint.create(
                station,
                "CP-01",
                "Trụ số 1",
                "Khu A",
                new BigDecimal("120.00")
        );
        chargePoint.setId(UUID.randomUUID());
        chargePoint.activate();
        Connector connector = Connector.create(
                chargePoint,
                "C-01",
                ConnectorType.CCS2,
                new BigDecimal("120.00"),
                ChargerType.DC
        );
        connector.setId(UUID.randomUUID());
        chargePoint.addConnector(connector);

        StationDiscoveryDetailResponse result = mapper.toResponse(
                station,
                schedule,
                List.of(chargePoint),
                new BigDecimal("3500.00"),
                StationOperatingStatus.open(),
                cancellationPolicySummary()
        );

        assertThat(result.id()).isEqualTo(stationId);
        assertThat(result.wardName()).isEqualTo("Phường Bến Nghé");
        assertThat(result.provinceName()).isEqualTo("Thành phố Hồ Chí Minh");
        assertThat(result.assets()).containsExactly(assetResponse);
        assertThat(result.currentPriceVndPerKwh()).isEqualByComparingTo("3500.00");
        assertThat(result.operationalStatus())
                .isEqualTo(com.thang.chargeops.common.enums.StationOperationalStatus.OPERATING);
        assertThat(result.open24Hours()).isFalse();
        assertThat(result.openNow()).isTrue();
        assertThat(result.operatingState()).isEqualTo(StationOperatingState.OPEN);
        assertThat(result.scheduleConfigured()).isTrue();
        assertThat(result.operatingHours()).hasSize(7);
        assertThat(result.operatingHours().get(0).day())
                .isEqualTo(StationDayOfWeek.MONDAY);
        assertThat(result.operatingHours().get(0).enabled()).isFalse();
        assertThat(result.operatingHours().get(1).openTime())
                .isEqualTo(LocalTime.of(8, 0));
        assertThat(result.operatingHours().get(2).enabled()).isFalse();
        assertThat(result.cancellationPolicy().policyVersion()).isEqualTo("booking-v4.9");
        assertThat(result.cancellationPolicy().gracePeriodMinutes()).isEqualTo(10);
        assertThat(result.cancellationPolicy().graceStartsAt()).isEqualTo("PAYMENT_CONFIRMED_AT");
        assertThat(result.cancellationPolicy().requiresBeforeBookingStart()).isTrue();
        assertThat(result.cancellationPolicy().requiresNotCheckedIn()).isTrue();
        assertThat(result.cancellationPolicy().withinGraceRefundPercent()).isEqualTo(100);
        assertThat(result.cancellationPolicy().afterGraceRefundPercent()).isZero();
        assertThat(result.cancellationPolicy().noShowRefundPercent()).isZero();
        assertThat(result.cancellationPolicy().verifiedStationFailureRefundPercent()).isEqualTo(100);
        assertThat(result.cancellationPolicy().stationFailureRequiresVerification()).isTrue();
        var json = tools.jackson.databind.json.JsonMapper.builder().build()
                .valueToTree(result.cancellationPolicy());
        assertThat(json.has("refundRules")).isFalse();
        assertThat(json.get("graceStartsAt").asString()).isEqualTo("PAYMENT_CONFIRMED_AT");
        assertThat(json.get("gracePeriodMinutes").asInt()).isEqualTo(10);

        assertThat(result.chargePoints()).singleElement().satisfies(point -> {
            assertThat(point.chargePointCode()).isEqualTo("CP-01");
            assertThat(point.operationalStatus())
                    .isEqualTo(OperationalChargePointStatus.AVAILABLE);
            assertThat(point.connectors()).singleElement().satisfies(mappedConnector -> {
                assertThat(mappedConnector.connectorCode()).isEqualTo("C-01");
                assertThat(mappedConnector.connectorType()).isEqualTo(ConnectorType.CCS2);
                assertThat(mappedConnector.availableNow()).isTrue();
            });
        });
    }

    @Test
    void mapsMissingScheduleAsClosedAndBlocksConnectorAtMaintenancePoint() {
        AdministrativeProvince province = mock(AdministrativeProvince.class);
        AdministrativeWard ward = mock(AdministrativeWard.class);
        when(ward.getProvince()).thenReturn(province);

        Station station = Station.builder()
                .ward(ward)
                .assets(List.of())
                .build();
        ChargePoint chargePoint = ChargePoint.create(
                station,
                "CP-02",
                null,
                null,
                new BigDecimal("60.00")
        );
        chargePoint.activate();
        chargePoint.updateOperationalStatus(OperationalChargePointStatus.MAINTENANCE);
        chargePoint.addConnector(Connector.create(
                chargePoint,
                "C-02",
                ConnectorType.CCS2,
                new BigDecimal("60.00"),
                ChargerType.DC
        ));

        StationDiscoveryDetailResponse result = mapper.toResponse(
                station,
                null,
                List.of(chargePoint),
                null,
                StationOperatingStatus.scheduleNotConfigured(),
                cancellationPolicySummary()
        );

        assertThat(result.open24Hours()).isFalse();
        assertThat(result.openNow()).isFalse();
        assertThat(result.operatingState())
                .isEqualTo(StationOperatingState.SCHEDULE_NOT_CONFIGURED);
        assertThat(result.scheduleConfigured()).isFalse();
        assertThat(result.operatingHours())
                .hasSize(7)
                .allSatisfy(day -> {
                    assertThat(day.enabled()).isFalse();
                    assertThat(day.openTime()).isNull();
                    assertThat(day.closeTime()).isNull();
                });
        assertThat(result.chargePoints().getFirst().connectors().getFirst().availableNow())
                .isFalse();
    }

    private CancellationPolicySummary cancellationPolicySummary() {
        return new CancellationPolicySummary(
                "booking-v4.9", 10, "PAYMENT_CONFIRMED_AT", true, true,
                100, 0, 0, 100, true
        );
    }
}
