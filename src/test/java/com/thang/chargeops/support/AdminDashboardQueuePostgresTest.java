package com.thang.chargeops.support;

import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.support.service.AdminDashboardQueueService;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.docker.compose.enabled=false"
})
@AutoConfigureTestDatabase(replace = NONE)
@Import(AdminDashboardQueueService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminDashboardQueuePostgresTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                    .asCompatibleSubstituteFor("postgres")
    );

    @Autowired JdbcTemplate jdbc;
    @Autowired AdminDashboardQueueService queues;
    @MockitoBean CurrentProfileProvider currentProfile;

    UUID reporter;
    UUID station;

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM ticket_escalations");
        jdbc.execute("DELETE FROM support_tickets");
        jdbc.execute("DELETE FROM stations");
        jdbc.execute("DELETE FROM user_profile");
        reporter = UUID.randomUUID();
        station = UUID.randomUUID();
        jdbc.update("INSERT INTO user_profile(id, keycloak_id, email, display_name, phone, status) VALUES (?, ?, ?, 'Reporter', '0123456789', 'ACTIVE')",
                reporter, reporter.toString(), reporter + "@test.local");
        jdbc.update("INSERT INTO stations(id, owner_id, name, address_line, contact_phone, status) VALUES (?, ?, 'Station A', 'Address', '0123456789', 'ACTIVE')",
                station, reporter);
        UserProfile actor = new UserProfile();
        actor.setStatus(UserStatus.ACTIVE);
        when(currentProfile.requireProfile()).thenReturn(actor);
    }

    @Test
    void queuesIncludeOnlyOpenWorkAndExposeMinimalFields() {
        UUID platformOpen = ticket(null, "TKT-20261009-1001", "OPEN", Instant.parse("2026-10-09T01:00:00Z"));
        UUID platformProgress = ticket(null, "TKT-20261009-1002", "IN_PROGRESS", Instant.parse("2026-10-09T02:00:00Z"));
        ticket(null, "TKT-20261009-1003", "RESOLVED", Instant.parse("2026-10-09T03:00:00Z"));
        UUID escalated = ticket(station, "TKT-20261009-1004", "OPEN", Instant.parse("2026-10-09T04:00:00Z"));
        UUID resolvedEscalation = ticket(station, "TKT-20261009-1005", "OPEN", Instant.parse("2026-10-09T05:00:00Z"));
        ticket(station, "TKT-20261009-1006", "CLOSED", Instant.parse("2026-10-09T06:00:00Z"));
        escalation(escalated, null);
        escalation(resolvedEscalation, Instant.parse("2026-10-09T07:00:00Z"));

        var platform = queues.platform(1, 1);
        assertThat(platform.getTotalElements()).isEqualTo(2);
        assertThat(platform.getContent()).extracting("ticketId").containsExactly(platformProgress);
        assertThat(queues.platform(2, 1).getContent()).extracting("ticketId").containsExactly(platformOpen);

        var cases = queues.escalated(1, 5);
        assertThat(cases.getTotalElements()).isEqualTo(1);
        assertThat(cases.getContent().getFirst().ticketId()).isEqualTo(escalated);
        assertThat(cases.getContent().getFirst().stationName()).isEqualTo("Station A");
        assertThat(cases.getContent().getFirst().escalationReason()).isEqualTo("Review needed");
        assertThat(com.thang.chargeops.support.dto.response.AdminDashboardTicketItem.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("refundIds", "totalAmount", "paymentAmount", "messages");
    }

    private UUID ticket(UUID stationId, String code, String status, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO support_tickets(id, ticket_code, category, status, priority, reporter_id,
                    station_id, subject, description, created_at, updated_at)
                VALUES (?, ?, 'OTHER', ?, 'MEDIUM', ?, ?, 'Help', 'Description', ?, ?)
                """, id, code, status, reporter, stationId, Timestamp.from(createdAt), Timestamp.from(createdAt));
        return id;
    }

    private void escalation(UUID ticketId, Instant resolvedAt) {
        jdbc.update("""
                INSERT INTO ticket_escalations(id, ticket_id, requested_by, requested_at, reason,
                    resolved_at, resolved_by, resolution_type, resolution_note)
                VALUES (?, ?, ?, ?, 'Review needed', ?, ?, ?, ?)
                """, UUID.randomUUID(), ticketId, reporter,
                Timestamp.from(Instant.parse("2026-10-09T04:30:00Z")),
                resolvedAt == null ? null : Timestamp.from(resolvedAt),
                resolvedAt == null ? null : reporter,
                resolvedAt == null ? null : "RETURN_TO_STATION",
                resolvedAt == null ? null : "Returned for follow-up");
    }
}
