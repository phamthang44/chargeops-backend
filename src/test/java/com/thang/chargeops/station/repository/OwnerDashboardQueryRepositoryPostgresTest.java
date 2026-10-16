package com.thang.chargeops.station.repository;

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
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.docker.compose.enabled=false"
})
@AutoConfigureTestDatabase(replace = NONE)
@Import(OwnerDashboardQueryRepository.class)
class OwnerDashboardQueryRepositoryPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine").asCompatibleSubstituteFor("postgres")
    );

    @Autowired
    private OwnerDashboardQueryRepository dashboardQueries;

    @Test
    void activityQueryAcceptsNullStationFilter() {
        var rows = dashboardQueries.countSessionsByLocalDate(
                UUID.randomUUID(), null,
                Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-02T00:00:00Z"));

        assertThat(rows).isEmpty();
    }

    @Test
    void activityCountsResolveBookingTimestampPaths() {
        UUID ownerId = UUID.randomUUID();
        Instant from = Instant.parse("2026-10-01T00:00:00Z");
        Instant to = Instant.parse("2026-10-02T00:00:00Z");

        assertThat(dashboardQueries.countSessions(ownerId, null, from, to)).isZero();
        assertThat(dashboardQueries.countScheduledBookings(ownerId, null, from, to)).isZero();
    }
}
