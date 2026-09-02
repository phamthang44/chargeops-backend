package com.thang.chargeops.station.repository;

import com.thang.chargeops.station.projection.StationDiscoveryItemProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.docker.compose.enabled=false"
})
@AutoConfigureTestDatabase(replace = NONE)
class StationDiscoveryRepositoryIntegrationTest {

    private static final Instant AT = Instant.parse("2026-08-31T05:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                    .asCompatibleSubstituteFor("postgres")
    );

    @Autowired
    private StationDiscoveryQueryRepository discoveryQueryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID ownerId;
    private UUID nearestStationId;
    private UUID cheaperStationId;

    @BeforeEach
    void setUpData() {
        jdbcTemplate.update(
                "INSERT INTO wards (code, name, full_name, province_code) VALUES (?, ?, ?, ?)",
                "TEST-DISCOVERY", "Discovery Ward", "Discovery Test Ward", "79"
        );

        ownerId = insertOwner();
        nearestStationId = insertStation(
                ownerId,
                "ST-DISC-001",
                "Central Fast Charge",
                "1 Le Loi",
                "10.776900",
                "106.700900"
        );
        cheaperStationId = insertStation(
                ownerId,
                "ST-DISC-002",
                "Budget Charge",
                "2 Le Loi",
                "10.800000",
                "106.700900"
        );

        insertPrimaryImage(nearestStationId, "https://cdn.test/station-1.jpg");
        insertPricing(nearestStationId, "3400.00");
        insertPricing(cheaperStationId, "2500.00");
        insertTouRate(nearestStationId, "DAILY", "2800.00");

        insertOpen24HoursSchedule(nearestStationId);
        insertOpen24HoursSchedule(cheaperStationId);

        UUID nearestChargePoint = insertChargePoint(
                nearestStationId,
                "CP-DISC-001",
                "150.00",
                "AVAILABLE"
        );
        insertConnector(nearestChargePoint, "C-001", "CCS2", "DC", "150.00", "AVAILABLE");
        insertConnector(nearestChargePoint, "C-002", "TYPE2", "AC", "22.00", "IN_USE");

        UUID cheaperChargePoint = insertChargePoint(
                cheaperStationId,
                "CP-DISC-002",
                "60.00",
                "MAINTENANCE"
        );
        insertConnector(cheaperChargePoint, "C-003", "CCS2", "DC", "60.00", "AVAILABLE");
    }

    @Test
    void filtersAndProjectsOneStationWithoutBreakingPagination() {
        Page<StationDiscoveryItemProjection> result = discoveryQueryRepository.findStations(
                baseParameters()
                        .queryPattern("%central%")
                        .provinceCode("79")
                        .connectorTypesEmpty(false)
                        .connectorTypes(List.of("CCS2"))
                        .chargerType("DC")
                        .minPowerKw(new BigDecimal("100.00"))
                        .availableOnly(true)
                        .openOnly(true)
                        .latitude(new BigDecimal("10.776900"))
                        .longitude(new BigDecimal("106.700900"))
                        .maxDistanceKm(new BigDecimal("5.00"))
                        .sort("NEAREST")
                        .build(),
                PageRequest.of(0, 12)
        );

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).singleElement().satisfies(station -> {
            assertThat(UUID.fromString(station.getId())).isEqualTo(nearestStationId);
            assertThat(station.getName()).isEqualTo("Central Fast Charge");
            assertThat(station.getAddressLine()).isEqualTo("1 Le Loi");
            assertThat(station.getDistanceKm()).isCloseTo(0.0, within(0.001));
            assertThat(station.getPrimaryImageUrl()).isEqualTo("https://cdn.test/station-1.jpg");
            assertThat(station.getPriceFromVndPerKwh()).isEqualByComparingTo("2800.00");
            assertThat(station.getMaxPowerKw()).isEqualByComparingTo("150.00");
            assertThat(station.getTotalConnectorCount()).isEqualTo(2L);
            assertThat(station.getAvailableConnectorCount()).isEqualTo(1L);
            assertThat(station.getOpenNow()).isTrue();
        });
    }

    @Test
    void sortsByCheapestAndReturnsDistinctConnectorTypesForThePage() {
        Page<StationDiscoveryItemProjection> result = discoveryQueryRepository.findStations(
                baseParameters()
                        .sort("CHEAPEST")
                        .build(),
                PageRequest.of(0, 1)
        );

        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).singleElement()
                .extracting(StationDiscoveryItemProjection::getId)
                .isEqualTo(cheaperStationId.toString());

        var connectorTypes = discoveryQueryRepository.findConnectorTypes(List.of(nearestStationId));
        assertThat(connectorTypes)
                .extracting(row -> row.getConnectorType().name())
                .containsExactlyInAnyOrder("CCS2", "TYPE2");
    }

    @Test
    void sortsByAvailableConnectorCount() {
        Page<StationDiscoveryItemProjection> result = discoveryQueryRepository.findStations(
                baseParameters().sort("AVAILABLE").build(),
                PageRequest.of(0, 2)
        );

        assertThat(result.getContent())
                .extracting(StationDiscoveryItemProjection::getId)
                .containsExactly(nearestStationId.toString(), cheaperStationId.toString());
    }

    @Test
    void sortsByNearestAndReturnsTheSecondOneBasedPageContent() {
        Page<StationDiscoveryItemProjection> result = discoveryQueryRepository.findStations(
                baseParameters()
                        .latitude(new BigDecimal("10.776900"))
                        .longitude(new BigDecimal("106.700900"))
                        .sort("NEAREST")
                        .build(),
                PageRequest.of(1, 1)
        );

        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).singleElement()
                .extracting(StationDiscoveryItemProjection::getId)
                .isEqualTo(cheaperStationId.toString());
    }

    @Test
    void usesStationIdAsStableTieBreaker() {
        UUID firstTieStation = insertDiscoverableStation(
                "ST-TIE-001", "Tie Station A", "3 Le Loi", "10.750000", "106.680000"
        );
        UUID secondTieStation = insertDiscoverableStation(
                "ST-TIE-002", "Tie Station B", "4 Le Loi", "10.750000", "106.680000"
        );

        Page<StationDiscoveryItemProjection> result = discoveryQueryRepository.findStations(
                baseParameters()
                        .queryPattern("%tie station%")
                        .latitude(new BigDecimal("10.750000"))
                        .longitude(new BigDecimal("106.680000"))
                        .sort("NEAREST")
                        .build(),
                PageRequest.of(0, 2)
        );

        List<String> expectedOrder = List.of(
                        firstTieStation.toString(),
                        secondTieStation.toString()
                ).stream()
                .sorted()
                .toList();

        assertThat(result.getContent())
                .extracting(StationDiscoveryItemProjection::getId)
                .containsExactlyElementsOf(expectedOrder);
    }

    @Test
    void includesAStationExactlyAtTheMaximumDistanceBoundary() {
        Page<StationDiscoveryItemProjection> withoutDistanceLimit =
                discoveryQueryRepository.findStations(
                        baseParameters()
                                .latitude(new BigDecimal("10.776900"))
                                .longitude(new BigDecimal("106.700900"))
                                .sort("NEAREST")
                                .build(),
                        PageRequest.of(0, 2)
                );
        double boundaryKm = withoutDistanceLimit.getContent().get(1).getDistanceKm();

        Page<StationDiscoveryItemProjection> atBoundary =
                discoveryQueryRepository.findStations(
                        baseParameters()
                                .latitude(new BigDecimal("10.776900"))
                                .longitude(new BigDecimal("106.700900"))
                                .maxDistanceKm(BigDecimal.valueOf(boundaryKm))
                                .sort("NEAREST")
                                .build(),
                        PageRequest.of(0, 12)
                );

        assertThat(atBoundary.getContent())
                .extracting(StationDiscoveryItemProjection::getId)
                .contains(nearestStationId.toString(), cheaperStationId.toString());
    }

    @Test
    void handlesOvernightClosedAndMissingOperatingSchedules() {
        UUID overnightStation = insertDiscoverableStation(
                "ST-OVERNIGHT", "Overnight Station", "5 Le Loi", "10.760000", "106.690000"
        );
        UUID closedStation = insertDiscoverableStation(
                "ST-CLOSED", "Closed Station", "6 Le Loi", "10.761000", "106.691000"
        );
        UUID noScheduleStation = insertDiscoverableStation(
                "ST-NO-SCHEDULE", "No Schedule Station", "7 Le Loi", "10.762000", "106.692000"
        );

        UUID overnightSchedule = insertSchedule(overnightStation, false);
        insertOperatingPeriod(overnightSchedule, "MONDAY", "22:00:00", "02:00:00");
        UUID closedSchedule = insertSchedule(closedStation, false);
        insertOperatingPeriod(closedSchedule, "TUESDAY", "09:00:00", "17:00:00");

        Page<StationDiscoveryItemProjection> result = discoveryQueryRepository.findStations(
                baseParameters()
                        .openOnly(true)
                        .localTime(LocalTime.of(1, 0))
                        .dayOfWeek("TUESDAY")
                        .previousDayOfWeek("MONDAY")
                        .build(),
                PageRequest.of(0, 12)
        );

        assertThat(result.getContent())
                .extracting(StationDiscoveryItemProjection::getId)
                .contains(overnightStation.toString())
                .doesNotContain(closedStation.toString(), noScheduleStation.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"%!%%", "%!_%", "%\\%"})
    void treatsSqlWildcardCharactersAsLiteralSearchText(String escapedPattern) {
        Page<StationDiscoveryItemProjection> result = discoveryQueryRepository.findStations(
                baseParameters()
                        .queryPattern(escapedPattern)
                        .build(),
                PageRequest.of(0, 12)
        );

        assertThat(result).isEmpty();
    }

    private StationDiscoveryQueryParameters.StationDiscoveryQueryParametersBuilder
    baseParameters() {
        return StationDiscoveryQueryParameters.builder()
                .connectorTypesEmpty(true)
                .connectorTypes(List.of("__NO_CONNECTOR_TYPE_FILTER__"))
                .availableOnly(false)
                .openOnly(false)
                .sort("AVAILABLE")
                .at(AT)
                .localTime(LocalTime.NOON)
                .dayOfWeek("MONDAY")
                .previousDayOfWeek("SUNDAY")
                .priceDayType("WEEKDAY");
    }

    private UUID insertDiscoverableStation(
            String stationCode,
            String name,
            String address,
            String latitude,
            String longitude
    ) {
        UUID stationId = insertStation(
                ownerId, stationCode, name, address, latitude, longitude
        );
        UUID chargePointId = insertChargePoint(
                stationId, "CP-" + stationCode, "22.00", "AVAILABLE"
        );
        insertConnector(
                chargePointId, "C-" + stationCode, "TYPE2", "AC", "22.00", "AVAILABLE"
        );
        return stationId;
    }

    private UUID insertOwner() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                        INSERT INTO user_profile
                            (id, keycloak_id, email, display_name, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                id,
                UUID.randomUUID().toString(),
                "discovery-owner@chargeops.test",
                "Discovery Owner",
                "ACTIVE",
                Timestamp.from(AT),
                Timestamp.from(AT)
        );
        return id;
    }

    private UUID insertStation(
            UUID stationOwnerId,
            String stationCode,
            String name,
            String address,
            String latitude,
            String longitude
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                        INSERT INTO stations
                            (id, station_code, owner_id, name, address_line, ward_code,
                             latitude, longitude, contact_phone, planned_charge_point_count,
                             status, version, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id,
                stationCode,
                stationOwnerId,
                name,
                address,
                "TEST-DISCOVERY",
                new BigDecimal(latitude),
                new BigDecimal(longitude),
                "0900000000",
                2,
                "ACTIVE",
                0L,
                Timestamp.from(AT),
                Timestamp.from(AT)
        );
        return id;
    }

    private void insertPrimaryImage(UUID stationId, String url) {
        jdbcTemplate.update(
                """
                        INSERT INTO station_assets
                            (id, station_id, asset_type, asset_url, display_order, is_primary,
                             created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(), stationId, "IMAGE", url, 0, true,
                Timestamp.from(AT), Timestamp.from(AT)
        );
    }

    private void insertPricing(UUID stationId, String basePrice) {
        jdbcTemplate.update(
                """
                        INSERT INTO station_booking_settings
                            (id, station_id, min_duration_minutes, duration_step_minutes,
                             max_duration_minutes, base_price_vnd, version, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(), stationId, 30, 30, 180,
                new BigDecimal(basePrice), 0L, Timestamp.from(AT), Timestamp.from(AT)
        );
    }

    private void insertTouRate(UUID stationId, String dayType, String price) {
        jdbcTemplate.update(
                """
                        INSERT INTO tou_rates
                            (id, station_id, name, period_code, day_type, start_time, end_time,
                             price_per_kwh, effective_from, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(), stationId, "Discovery rate", "NORMAL", dayType,
                Time.valueOf("00:00:00"), Time.valueOf("23:59:00"), new BigDecimal(price),
                Timestamp.from(AT.minusSeconds(3600)), Timestamp.from(AT), Timestamp.from(AT)
        );
    }

    private void insertOpen24HoursSchedule(UUID stationId) {
        insertSchedule(stationId, true);
    }

    private UUID insertSchedule(UUID stationId, boolean open24Hours) {
        UUID scheduleId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                        INSERT INTO station_operating_schedules
                            (id, station_id, open_24_hours, effective_from, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                scheduleId, stationId, open24Hours, Timestamp.from(AT.minusSeconds(3600)),
                Timestamp.from(AT), Timestamp.from(AT)
        );
        return scheduleId;
    }

    private void insertOperatingPeriod(
            UUID scheduleId,
            String dayOfWeek,
            String openTime,
            String closeTime
    ) {
        jdbcTemplate.update(
                """
                        INSERT INTO station_operating_periods
                            (id, schedule_id, day_of_week, open_time, close_time, is_enabled,
                             created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(), scheduleId, dayOfWeek, Time.valueOf(openTime),
                Time.valueOf(closeTime), true, Timestamp.from(AT), Timestamp.from(AT)
        );
    }

    private UUID insertChargePoint(
            UUID stationId,
            String code,
            String maxPower,
            String operationalStatus
    ) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                        INSERT INTO charge_points
                            (id, station_id, charge_point_code, max_power_kw, provisioning_status,
                             operational_status, version, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                id, stationId, code, new BigDecimal(maxPower), "ACTIVE", operationalStatus,
                0L, Timestamp.from(AT), Timestamp.from(AT)
        );
        return id;
    }

    private void insertConnector(
            UUID chargePointId,
            String code,
            String connectorType,
            String chargerType,
            String power,
            String runtimeStatus
    ) {
        jdbcTemplate.update(
                """
                        INSERT INTO connectors
                            (id, charge_point_id, connector_code, connector_type, power_kw,
                             charger_type, runtime_status, version, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(), chargePointId, code, connectorType, new BigDecimal(power),
                chargerType, runtimeStatus, 0L, Timestamp.from(AT), Timestamp.from(AT)
        );
    }

    private org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}