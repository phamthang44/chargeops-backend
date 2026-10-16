package com.thang.chargeops.support;

import com.thang.chargeops.common.enums.UserStatus;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.support.service.AdminDashboardQueueService;
import com.thang.chargeops.support.service.AdminOperationsSummaryService;
import com.thang.chargeops.support.service.impl.AdminDashboardQueueServiceImpl;
import com.thang.chargeops.support.repository.AdminDashboardReadRepository;
import com.thang.chargeops.support.model.AdminDashboardSort;
import com.thang.chargeops.support.model.AdminDashboardQueueScope;
import com.thang.chargeops.exception.AppException;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.access.AccessDeniedException;
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
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.docker.compose.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.stat=OFF"
})
@AutoConfigureTestDatabase(replace = NONE)
@Import({AdminDashboardQueueServiceImpl.class, AdminDashboardReadRepository.class, AdminOperationsSummaryService.class,
        AdminDashboardQueuePostgresTest.TestConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@WithMockUser(roles = "ADMIN")
class AdminDashboardQueuePostgresTest {
    static final Instant NOW = Instant.parse("2026-10-16T08:00:00Z");

    @TestConfiguration
    @EnableMethodSecurity
    static class TestConfig {
        @Bean Clock applicationClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                    .asCompatibleSubstituteFor("postgres")
    );

    @Autowired JdbcTemplate jdbc;
    @Autowired AdminDashboardQueueService queues;
    @Autowired AdminOperationsSummaryService legacySummary;
    @MockitoBean CurrentProfileProvider currentProfile;
    @Autowired EntityManagerFactory entityManagerFactory;

    UUID reporter;
    UUID station;

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("vi"));
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

    @AfterEach
    void resetLocale() { LocaleContextHolder.resetLocaleContext(); }

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

        var platform = queues.platform(1, 1, AdminDashboardSort.NEWEST_FIRST).page();
        assertThat(platform.getTotalElements()).isEqualTo(2);
        assertThat(platform.getContent()).extracting("ticketId").containsExactly(platformProgress);
        assertThat(queues.platform(2, 1, AdminDashboardSort.NEWEST_FIRST).page().getContent())
                .extracting("ticketId").containsExactly(platformOpen);

        var cases = queues.escalated(1, 5, AdminDashboardSort.NEWEST_FIRST).page();
        assertThat(cases.getTotalElements()).isEqualTo(1);
        assertThat(cases.getContent().getFirst().ticketId()).isEqualTo(escalated);
        assertThat(cases.getContent().getFirst().stationName()).isEqualTo("Station A");
        assertThat(cases.getContent().getFirst().escalationReason()).isEqualTo("Review needed");
        assertThat(com.thang.chargeops.support.dto.response.AdminDashboardTicketItem.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("refundIds", "totalAmount", "paymentAmount", "messages");
    }

    @Test
    void oldestFirstUsesEscalationRequestTimeWhileNewestKeepsLegacyTicketTime() {
        UUID oldTicket = ticket(station, "TKT-20261016-2001", "OPEN", Instant.parse("2026-10-01T00:00:00Z"));
        UUID newTicket = ticket(station, "TKT-20261016-2002", "IN_PROGRESS", Instant.parse("2026-10-08T00:00:00Z"));
        escalation(oldTicket, null);
        escalation(newTicket, null);
        jdbc.update("UPDATE ticket_escalations SET requested_at=? WHERE ticket_id=?",
                Timestamp.from(Instant.parse("2026-10-15T00:00:00Z")), oldTicket);
        jdbc.update("UPDATE ticket_escalations SET requested_at=? WHERE ticket_id=?",
                Timestamp.from(Instant.parse("2026-10-09T00:00:00Z")), newTicket);

        var oldest = queues.escalated(1, 1, AdminDashboardSort.OLDEST_FIRST);
        assertThat(oldest.generatedAt()).isEqualTo(NOW);
        assertThat(oldest.scope()).isEqualTo(AdminDashboardQueueScope.ESCALATED_CASES);
        assertThat(oldest.page().getTotalElements()).isEqualTo(2);
        assertThat(oldest.page().getContent()).extracting("ticketId").containsExactly(newTicket);
        assertThat(oldest.page().getContent().getFirst().requestedAt())
                .isEqualTo(Instant.parse("2026-10-09T00:00:00Z"));
        assertThat(oldest.page().getContent().getFirst().escalationId()).isNotNull();
        assertThat(queues.escalated(2, 1, AdminDashboardSort.OLDEST_FIRST).page().getContent())
                .extracting("ticketId").containsExactly(oldTicket);
        assertThat(queues.escalated(1, 1, AdminDashboardSort.NEWEST_FIRST).page().getContent())
                .extracting("ticketId").containsExactly(newTicket);
    }

    @Test
    void platformSortIsStableForEqualTimestampsAndProjectsActualHandler() {
        Instant created = Instant.parse("2026-10-09T01:00:00Z");
        UUID first = ticket(null, "TKT-20261016-2003", "OPEN", created);
        UUID second = ticket(null, "TKT-20261016-2004", "IN_PROGRESS", created);
        // PostgreSQL UUID ordering follows the unsigned hexadecimal representation.
        UUID low = first.toString().compareTo(second.toString()) < 0 ? first : second;
        UUID high = low.equals(first) ? second : first;
        jdbc.update("UPDATE support_tickets SET assigned_handler_id=? WHERE id=?", reporter, low);

        var row = queues.platform(1, 1, AdminDashboardSort.OLDEST_FIRST).page().getContent().getFirst();
        assertThat(row.ticketId()).isEqualTo(low);
        assertThat(row.assignedHandlerId()).isEqualTo(reporter);
        assertThat(row.assignedHandlerName()).isEqualTo("Reporter");
        assertThat(row.escalationId()).isNull();
        assertThat(row.requestedAt()).isNull();
        var next = queues.platform(2, 1, AdminDashboardSort.OLDEST_FIRST).page().getContent().getFirst();
        assertThat(next.ticketId()).isEqualTo(high);
        assertThat(next.assignedHandlerId()).isNull();
        assertThat(queues.platform(1, 1, AdminDashboardSort.NEWEST_FIRST).page().getContent())
                .extracting("ticketId").containsExactly(high);
    }

    @Test
    void approvalsUseSubmissionTimeAndKeepCountConsistentForLegacyMissingLocation() {
        UUID first = pending("Pending first", Instant.parse("2026-10-01T00:00:00Z"));
        UUID second = pending("Pending second", Instant.parse("2026-10-02T00:00:00Z"));
        UUID deleted = pending("Deleted", Instant.parse("2026-09-01T00:00:00Z"));
        jdbc.update("UPDATE stations SET deleted_at=now() WHERE id=?", deleted);
        jdbc.update("UPDATE stations SET updated_at=now() WHERE id=?", first);
        jdbc.update("UPDATE user_profile SET display_name='' WHERE id=?", reporter);
        String ward = jdbc.queryForObject("SELECT code FROM wards ORDER BY code LIMIT 1", String.class);
        jdbc.update("UPDATE stations SET ward_code=? WHERE id=?", ward, second);
        String vi = jdbc.queryForObject("SELECT p.full_name FROM provinces p JOIN wards w ON w.province_code=p.code WHERE w.code=?",
                String.class, ward);
        String en = jdbc.queryForObject("SELECT coalesce(nullif(p.full_name_en, ''), p.full_name) FROM provinces p JOIN wards w ON w.province_code=p.code WHERE w.code=?",
                String.class, ward);

        var result = queues.approvals(1, 1, AdminDashboardSort.OLDEST_FIRST);
        assertThat(result.page().getTotalElements()).isEqualTo(2);
        assertThat(result.page().getContent()).extracting("stationId").containsExactly(first);
        assertThat(result.page().getContent().getFirst().submittedAt()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(result.page().getContent().getFirst().provinceName()).isNull();
        assertThat(result.page().getContent().getFirst().ownerDisplayName()).isEqualTo(reporter + "@test.local");
        assertThat(queues.approvals(2, 1, AdminDashboardSort.OLDEST_FIRST).page().getContent().getFirst().provinceName())
                .isEqualTo(vi);
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertThat(queues.approvals(1, 1, AdminDashboardSort.NEWEST_FIRST).page().getContent().getFirst().provinceName())
                .isEqualTo(en);
        assertThat(queues.approvals(3, 1, AdminDashboardSort.OLDEST_FIRST).page().getContent()).isEmpty();
        assertThat(queues.summary().pendingApprovals()).isEqualTo(2);
        assertThat(queues.summary().activeStations()).isEqualTo(1);
    }

    @Test
    void closingWorkRemovesItFromBothSummaryAndQueues() {
        UUID platform = ticket(null, "TKT-20261016-2005", "IN_PROGRESS", NOW.minusSeconds(200));
        UUID escalated = ticket(station, "TKT-20261016-2006", "OPEN", NOW.minusSeconds(300));
        UUID closed = ticket(station, "TKT-20261016-2007", "CLOSED", NOW.minusSeconds(400));
        escalation(escalated, null);
        escalation(closed, null);
        assertThat(queues.summary().platformOpenTickets()).isEqualTo(1);
        assertThat(queues.summary().escalatedOpenCases()).isEqualTo(1);
        assertThat(queues.summary().generatedAt()).isEqualTo(NOW);
        assertThat(legacySummary.dashboard()).isEqualTo(queues.summary());
        jdbc.update("UPDATE support_tickets SET status='RESOLVED' WHERE id=?", platform);
        jdbc.update("UPDATE ticket_escalations SET resolved_at=?, resolved_by=?, resolution_type='RETURN_TO_STATION', resolution_note='Returned' WHERE ticket_id=?",
                Timestamp.from(NOW), reporter, escalated);
        assertThat(queues.summary().platformOpenTickets()).isZero();
        assertThat(queues.summary().escalatedOpenCases()).isZero();
        assertThat(queues.platform(1, 5, AdminDashboardSort.OLDEST_FIRST).page().getTotalElements()).isZero();
        assertThat(queues.escalated(1, 5, AdminDashboardSort.OLDEST_FIRST).page().getTotalElements()).isZero();
    }

    @Test
    void legacyDuplicateActiveEscalationsChooseLatestTimeThenIdWithoutDoubleCounting() {
        UUID ticket = ticket(station, "TKT-20261016-2008", "OPEN", NOW.minusSeconds(1000));
        escalation(ticket, null);
        UUID winner = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        jdbc.execute("DROP INDEX ux_ticket_escalations_active_ticket");
        try {
            jdbc.update("INSERT INTO ticket_escalations(id, ticket_id, requested_by, requested_at, reason) VALUES (?, ?, ?, ?, 'Latest')",
                    UUID.fromString("00000000-0000-0000-0000-000000000001"), ticket, reporter, Timestamp.from(NOW));
            jdbc.update("INSERT INTO ticket_escalations(id, ticket_id, requested_by, requested_at, reason) VALUES (?, ?, ?, ?, 'Tie winner')",
                    winner, ticket, reporter, Timestamp.from(NOW));
            for (var sort : AdminDashboardSort.values()) {
                var page = queues.escalated(1, 5, sort).page();
                assertThat(page.getTotalElements()).isEqualTo(1);
                assertThat(page.getContent()).hasSize(1);
                assertThat(page.getContent().getFirst().escalationId()).isEqualTo(winner);
                assertThat(page.getContent().getFirst().escalationReason()).isEqualTo("Tie winner");
            }
            assertThat(queues.summary().escalatedOpenCases()).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM ticket_escalations WHERE ticket_id=?", ticket);
            jdbc.execute("CREATE UNIQUE INDEX ux_ticket_escalations_active_ticket ON ticket_escalations(ticket_id) WHERE resolved_at IS NULL");
        }
    }

    @Test
    void projectionsUseTwoQueriesRegardlessOfRowCount() {
        for (int i = 0; i < 12; i++) ticket(null, "TKT-20261016-" + (3000 + i), "OPEN", NOW.minusSeconds(i));
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        var result = queues.platform(1, 10, AdminDashboardSort.OLDEST_FIRST);
        assertThat(result.page().getContent()).hasSize(10);
        assertThat(result.page().getTotalElements()).isEqualTo(12);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
        assertThat(statistics.getEntityLoadCount()).isZero();
    }

    @Test
    void suspendedAdminAndInvalidPaginationAreRejected() {
        UserProfile suspended = new UserProfile();
        suspended.setStatus(UserStatus.SUSPENDED);
        when(currentProfile.requireProfile()).thenReturn(suspended);
        assertThatThrownBy(queues::summary).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> queues.approvals(1, 5, AdminDashboardSort.OLDEST_FIRST)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> queues.platform(1, 5, AdminDashboardSort.OLDEST_FIRST)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> queues.escalated(1, 5, AdminDashboardSort.OLDEST_FIRST)).isInstanceOf(AppException.class);
        suspended.setStatus(UserStatus.ACTIVE);
        assertThatThrownBy(() -> queues.platform(0, 5, AdminDashboardSort.OLDEST_FIRST)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> queues.platform(1, 101, AdminDashboardSort.OLDEST_FIRST)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> queues.platform(Integer.MAX_VALUE, 100, AdminDashboardSort.OLDEST_FIRST)).isInstanceOf(AppException.class);
    }

    @Test
    @WithMockUser(roles = "OWNER")
    void serviceRoleGuardAlsoProtectsLegacySummaryDelegation() {
        assertThatThrownBy(queues::summary).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(legacySummary::dashboard).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> queues.approvals(1, 5, AdminDashboardSort.OLDEST_FIRST))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> queues.platform(1, 5, AdminDashboardSort.OLDEST_FIRST))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> queues.escalated(1, 5, AdminDashboardSort.OLDEST_FIRST))
                .isInstanceOf(AccessDeniedException.class);
    }

    private UUID pending(String name, Instant submittedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO stations(id, owner_id, name, address_line, contact_phone, status, created_at, updated_at) VALUES (?, ?, ?, 'Address', '0123456789', 'PENDING_APPROVAL', ?, ?)",
                id, reporter, name, Timestamp.from(submittedAt), Timestamp.from(submittedAt));
        return id;
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
