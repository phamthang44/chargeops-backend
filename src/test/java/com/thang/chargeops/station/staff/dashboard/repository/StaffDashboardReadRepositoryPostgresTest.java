package com.thang.chargeops.station.staff.dashboard.repository;

import com.thang.chargeops.station.staff.dashboard.StaffDashboardBookingView;
import jakarta.persistence.NoResultException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.docker.compose.enabled=false"
})
@AutoConfigureTestDatabase(replace = NONE)
@Import(StaffDashboardReadRepository.class)
class StaffDashboardReadRepositoryPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine").asCompatibleSubstituteFor("postgres")
    );

    @Autowired
    private StaffDashboardReadRepository dashboardQueries;

    @Test
    void bookingListsParseForBothViews() {
        UUID stationId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-15T06:30:00Z");
        Instant dayEnd = Instant.parse("2026-10-15T17:00:00Z");

        for (StaffDashboardBookingView view : StaffDashboardBookingView.values()) {
            assertThat(dashboardQueries.countBookings(stationId, view, now, dayEnd)).isZero();
            assertThat(dashboardQueries.bookings(stationId, view, now, dayEnd, 0, 5)).isEmpty();
        }
    }

    @Test
    void ticketListParsesForBothViews() {
        UUID stationId = UUID.randomUUID();
        UUID handlerId = UUID.randomUUID();

        for (boolean unclaimed : new boolean[]{true, false}) {
            assertThat(dashboardQueries.countTickets(stationId, handlerId, unclaimed)).isZero();
            assertThat(dashboardQueries.tickets(stationId, handlerId, unclaimed, 0, 5)).isEmpty();
        }
    }

    @Test
    void summaryAndEquipmentQueriesParse() {
        UUID stationId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-15T06:30:00Z");

        assertThrows(NoResultException.class, () -> dashboardQueries.summary(stationId, now));
        assertThat(dashboardQueries.countEquipmentChargePoints(stationId)).isZero();
        assertThat(dashboardQueries.countEquipmentConnectors(stationId)).isZero();
        assertThat(dashboardQueries.equipmentChargePoints(stationId, 0, 5)).isEmpty();
        assertThat(dashboardQueries.equipmentConnectors(stationId, 0, 5)).isEmpty();
        assertThat(dashboardQueries.equipment(stationId, 0, 5)).isEmpty();
    }
}
