package com.thang.chargeops.station.service;

import com.thang.chargeops.booking.repository.OwnerBookingReadRepository;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.repository.*;
import com.thang.chargeops.station.service.usecase.OwnerStationUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "spring.docker.compose.enabled=false"})
@AutoConfigureTestDatabase(replace = NONE)
@Import(OwnerStationUseCase.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OwnerOperationsSummaryPostgresTest {
    private static final Instant NOW = Instant.parse("2026-10-05T18:00:00Z");
    private static final Instant DAY_START = Instant.parse("2026-10-05T17:00:00Z");

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine").asCompatibleSubstituteFor("postgres"));

    @Autowired JdbcTemplate jdbc;
    @Autowired OwnerStationUseCase ownerUseCase;
    @Autowired StationRepository stations;
    @Autowired ChargePointRepository points;
    @Autowired ConnectorRepository connectors;
    @Autowired OwnerBookingReadRepository bookings;
    @MockitoBean CurrentProfileProvider profiles;
    @MockitoBean Clock clock;
    @MockitoBean com.thang.chargeops.station.mapper.StationMapper mapper;
    @MockitoBean com.thang.chargeops.station.service.support.StationEffectiveStateResolver stateResolver;
    @MockitoBean com.thang.chargeops.station.policy.StationOperationalStatusPolicy operationalPolicy;

    @Test
    void jpaSummariesRespectScopeSoftDeletionProvisioningAndEffectiveLicense() {
        UUID owner = profile();
        UUID otherOwner = profile();
        UUID driver = profile();
        UUID station = station(owner, "ACTIVE");
        station(owner, "PENDING_APPROVAL");
        UUID deletedStation = station(owner, "ACTIVE");
        jdbc.update("UPDATE stations SET deleted_at = now() WHERE id = ?", deletedStation);
        UUID otherStation = station(otherOwner, "ACTIVE");
        UUID point = point(station, "ACTIVE", "AVAILABLE");
        UUID maintenance = point(station, "ACTIVE", "MAINTENANCE");
        UUID offlinePoint = point(station, "ACTIVE", "OFFLINE");
        UUID deletedPoint = point(station, "ACTIVE", "AVAILABLE");
        jdbc.update("UPDATE charge_points SET deleted_at = now() WHERE id = ?", deletedPoint);
        connector(deletedPoint, "AVAILABLE");
        connector(point(deletedStation, "ACTIVE", "AVAILABLE"), "AVAILABLE");
        connector(point(station, "PENDING_ACTIVATION", "AVAILABLE"), "AVAILABLE");
        UUID otherConnector = connector(point(otherStation, "ACTIVE", "AVAILABLE"), "AVAILABLE");
        UUID first = connector(point, "IN_USE");
        UUID second = connector(point, "AVAILABLE");
        connector(point, "OFFLINE");
        connector(maintenance, "AVAILABLE");
        connector(offlinePoint, "AVAILABLE");
        UUID deletedConnector = connector(point, "AVAILABLE");
        jdbc.update("UPDATE connectors SET deleted_at = now() WHERE id = ?", deletedConnector);
        license(station, owner, NOW.minusSeconds(3600), NOW.plusSeconds(3600));
        // More than one connector must not multiply station counts.
        booking(driver, first, DAY_START.minusSeconds(3600), DAY_START.plusSeconds(1800), NOW);
        booking(driver, second, DAY_START.plusSeconds(1800), null, NOW.plusSeconds(3600));
        booking(driver, otherConnector, DAY_START, null, NOW.plusSeconds(3600));
        booking(driver, deletedConnector, DAY_START, null, NOW.plusSeconds(3600));
        booking(driver, first, DAY_START.minusSeconds(7200), DAY_START, DAY_START);
        var profile = mock(UserProfile.class);
        when(profiles.requireProfile()).thenReturn(profile);
        when(profile.getId()).thenReturn(owner);
        when(clock.instant()).thenReturn(NOW);

        var result = ownerUseCase.getOperationsSummary();
        assertThat(result.stations().totalStations()).isEqualTo(2);
        assertThat(result.stations().activeStations()).isEqualTo(1);
        assertThat(result.stations().visibleToDrivers()).isEqualTo(1);
        assertThat(result.stations().pendingApproval()).isEqualTo(1);
        assertThat(result.stations().totalChargePoints()).isEqualTo(3);
        assertThat(result.stations().onlineChargePoints()).isEqualTo(1);
        assertThat(result.hardware().totalConnectors()).isEqualTo(5);
        assertThat(result.hardware().availableConnectors()).isEqualTo(1);
        assertThat(result.hardware().chargingConnectors()).isEqualTo(1);
        assertThat(result.hardware().offlineConnectors()).isEqualTo(1);
        assertThat(result.hardware().unavailableConnectors()).isEqualTo(2);
        assertThat(result.hardware().sessionsToday()).isEqualTo(1);
        assertThat(result.hardware().averageUtilizationPercent()).isEqualByComparingTo("20.00");

        jdbc.update("UPDATE licenses SET expires_at = ? WHERE station_id = ?", Timestamp.from(NOW), station);
        assertThat(ownerUseCase.getOperationsSummary().stations().visibleToDrivers()).isZero();

        UUID emptyOwner = profile();
        assertThat(stations.summarizeOwnerOperations(emptyOwner, NOW).getTotalStations()).isZero();
        assertThat(stations.summarizeOwnerOperations(emptyOwner, NOW).getVisibleToDrivers()).isZero();
        assertThat(points.summarizeOwnerOperations(emptyOwner).getOnlineChargePoints()).isZero();
        assertThat(connectors.summarizeOwnerOperations(emptyOwner).getUnavailableConnectors()).isZero();
        assertThat(bookings.findOwnerChargingWindows(emptyOwner, DAY_START, NOW)).isEmpty();
    }

    private UUID profile() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_profile(id, keycloak_id, email, display_name, status) VALUES (?, ?, ?, 'Summary Test', 'ACTIVE')",
                id, id.toString(), id + "@summary.test");
        return id;
    }

    private UUID station(UUID owner, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO stations(id, owner_id, name, address_line, contact_phone, status) VALUES (?, ?, 'Summary Test', '123 Test St', '0123456789', ?)",
                id, owner, status);
        return id;
    }

    private UUID point(UUID station, String provisioning, String operational) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO charge_points(id, station_id, charge_point_code, provisioning_status, operational_status) VALUES (?, ?, ?, ?, ?)",
                id, station, id.toString(), provisioning, operational);
        return id;
    }

    private UUID connector(UUID point, String runtime) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO connectors(id, charge_point_id, connector_code, connector_type, power_kw, charger_type, runtime_status) VALUES (?, ?, ?, 'CCS2', 60, 'DC', ?)",
                id, point, id.toString(), runtime);
        return id;
    }

    private void license(UUID station, UUID owner, Instant from, Instant to) {
        jdbc.update("INSERT INTO licenses(id, license_code, station_id, owner_id, plan, fee_amount, start_at, expires_at, status) VALUES (?, ?, ?, ?, 'MONTHLY', 0, ?, ?, 'ACTIVE')",
                UUID.randomUUID(), "LIC-SUM-" + UUID.randomUUID().toString().substring(0, 8),
                station, owner, Timestamp.from(from), Timestamp.from(to));
    }

    private void booking(UUID driver, UUID connector, Instant started, Instant completed, Instant end) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bookings(id, driver_id, connector_id, booking_code, start_at, end_at, status,
                    total_amount, station_name_snapshot, station_address_snapshot, charge_point_code_snapshot,
                    connector_code_snapshot, charging_started_at, completed_at, policy_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, 10000, 'Station', 'Address', 'CP', 'CON', ?, ?, 'booking-v4.9')
                """, id, driver, connector, "BK-" + id.toString().substring(0, 8), Timestamp.from(started),
                Timestamp.from(end), completed == null ? "CHARGING" : "COMPLETED", Timestamp.from(started),
                completed == null ? null : Timestamp.from(completed));
    }
}
