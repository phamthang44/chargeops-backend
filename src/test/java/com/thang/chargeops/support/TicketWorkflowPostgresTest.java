package com.thang.chargeops.support;

import com.thang.chargeops.infra.identity.IdentityRoleService;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.support.dto.request.TicketStatusRequest;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.model.TicketCategory;
import com.thang.chargeops.support.model.TicketPriority;
import com.thang.chargeops.support.model.TicketStatus;
import com.thang.chargeops.support.repository.SupportTicketRepository;
import com.thang.chargeops.support.service.impl.TicketWorkflowService;
import com.thang.chargeops.support.service.TicketEventQueryService;
import com.thang.chargeops.support.service.TicketKpiService;
import com.thang.chargeops.support.service.TicketAccessPolicy;
import com.thang.chargeops.notification.service.NotificationService;
import com.thang.chargeops.notification.service.NotificationInboxService;
import com.thang.chargeops.support.service.support.TicketResponseMapper;
import com.thang.chargeops.support.service.support.TicketResponseService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true", "spring.docker.compose.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TicketWorkflowService.class, TicketEventQueryService.class, TicketKpiService.class,
    TicketAccessPolicy.class, NotificationService.class, NotificationInboxService.class,
    TicketResponseMapper.class, TicketResponseService.class, TicketWorkflowPostgresTest.TestClock.class})
class TicketWorkflowPostgresTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
        DockerImageName.parse("postgis/postgis:17-3.5-alpine").asCompatibleSubstituteFor("postgres"));

    @TestConfiguration
    static class TestClock {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC); }
    }

    @Autowired TicketWorkflowService workflow;
    @Autowired TicketEventQueryService events;
    @Autowired TicketKpiService kpis;
    @Autowired NotificationInboxService inbox;
    @Autowired SupportTicketRepository tickets;
    @Autowired UserProfileRepository profiles;
    @Autowired StationRepository stations;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @MockitoBean CurrentProfileProvider currentProfile;
    @MockitoBean IdentityRoleService identityRoles;

    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void twoStaffClaimingSameTicketProduceOneClaimEvent() throws Exception {
        jdbc.execute("""
            INSERT INTO user_profile(keycloak_id,email,display_name,status)
            VALUES ('race-owner','race-owner@example.test','Owner','ACTIVE'),
                   ('race-one','race-one@example.test','Staff one','ACTIVE'),
                   ('race-two','race-two@example.test','Staff two','ACTIVE'),
                   ('race-reporter','race-reporter@example.test','Reporter','ACTIVE');
            INSERT INTO stations(owner_id,name,address_line,contact_phone,status)
            SELECT id,'Race station','Test','000','ACTIVE' FROM user_profile WHERE keycloak_id='race-owner';
            INSERT INTO station_staff_assignments(station_id,user_id,assigned_by,status)
            SELECT s.id,u.id,o.id,'ACTIVE' FROM stations s
            JOIN user_profile u ON u.keycloak_id IN ('race-one','race-two')
            JOIN user_profile o ON o.keycloak_id='race-owner'
            WHERE s.name='Race station';
            INSERT INTO support_tickets(ticket_code,category,status,priority,reporter_id,station_id,subject,description)
            SELECT 'TKT-20261001-9997','BOOKING','OPEN','MEDIUM',r.id,s.id,'Race ticket','Test concurrent claim'
            FROM user_profile r CROSS JOIN stations s WHERE r.keycloak_id='race-reporter' AND s.name='Race station';
            """);
        var ticketId = jdbc.queryForObject("SELECT id FROM support_tickets WHERE ticket_code='TKT-20261001-9997'", java.util.UUID.class);
        var first = profiles.findByKeycloakId("race-one").orElseThrow();
        var second = profiles.findByKeycloakId("race-two").orElseThrow();
        doAnswer(invocation -> Thread.currentThread().getName().equals("first-claim") ? first : second)
            .when(currentProfile).requireProfile();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> claimOnThread("first-claim", ticketId, ready, start));
            var two = pool.submit(() -> claimOnThread("second-claim", ticketId, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int successes = (one.get(10, TimeUnit.SECONDS) ? 1 : 0) + (two.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertThat(successes).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_events WHERE ticket_id=? AND event_type='CLAIMED'",
            Long.class, ticketId)).isEqualTo(1);
    }

    private boolean claimOnThread(String name, java.util.UUID ticketId, CountDownLatch ready, CountDownLatch start) {
        Thread.currentThread().setName(name);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            name, "", List.of(new SimpleGrantedAuthority("ROLE_DRIVER"))));
        try {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) return false;
            workflow.claim(ticketId, 0);
            return true;
        } catch (com.thang.chargeops.exception.AppException conflict) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void activeStationStaffClaimsAndRevocationReturnsTicketToQueue() {
        jdbc.execute("""
            INSERT INTO user_profile(keycloak_id,email,display_name,status)
            VALUES ('staff-owner','staff-owner@example.test','Owner','ACTIVE'),
                   ('staff-worker','staff-worker@example.test','Staff','ACTIVE'),
                   ('staff-reporter','staff-reporter@example.test','Reporter','ACTIVE');
            INSERT INTO stations(owner_id,name,address_line,contact_phone,status)
            SELECT id,'Staff workflow station','Test','000','ACTIVE' FROM user_profile WHERE keycloak_id='staff-owner';
            INSERT INTO station_staff_assignments(station_id,user_id,assigned_by,status)
            SELECT s.id,u.id,o.id,'ACTIVE' FROM stations s
            JOIN user_profile u ON u.keycloak_id='staff-worker'
            JOIN user_profile o ON o.keycloak_id='staff-owner'
            WHERE s.name='Staff workflow station';
            """);
        entityManager.clear();
        var owner = profiles.findByKeycloakId("staff-owner").orElseThrow();
        var staff = profiles.findByKeycloakId("staff-worker").orElseThrow();
        var reporter = profiles.findByKeycloakId("staff-reporter").orElseThrow();
        var stationId = jdbc.queryForObject("SELECT id FROM stations WHERE name='Staff workflow station'", java.util.UUID.class);
        var station = stations.findById(stationId).orElseThrow();
        var ticket = tickets.saveAndFlush(SupportTicket.open("TKT-20261001-9998", TicketCategory.BOOKING,
            TicketPriority.MEDIUM, reporter, station, null,
            new SupportTicket.TicketDetails("Booking issue", "Could not charge")));
        when(currentProfile.requireProfile()).thenReturn(staff);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            "staff", "", List.of(new SimpleGrantedAuthority("ROLE_DRIVER"))));

        workflow.claim(ticket.getId(), ticket.getVersion());
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getAssignedHandler().getId()).isEqualTo(staff.getId());
        assertThat(kpis.get(stationId, null, Instant.parse("2026-09-30T00:00:00Z"),
            Instant.parse("2026-10-02T00:00:00Z")).selfClaimedTickets()).isEqualTo(1);

        jdbc.update("UPDATE station_staff_assignments SET status='REVOKED', revoked_by=?, revoked_at=now() WHERE user_id=?",
            owner.getId(), staff.getId());
        workflow.releaseRevokedStaff(stationId, staff.getId(), owner.getId());
        var released = tickets.findById(ticket.getId()).orElseThrow();
        assertThat(released.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(released.getAssignedHandler()).isNull();
    }

    @Test
    void ownerCanQueryEmptyStationKpisOnPostgres() {
        jdbc.execute("""
            INSERT INTO user_profile(keycloak_id,email,display_name,status)
            VALUES ('kpi-owner','kpi-owner@example.test','Owner','ACTIVE');
            INSERT INTO stations(owner_id,name,address_line,contact_phone,status)
            SELECT id,'KPI station','Test','000','ACTIVE' FROM user_profile WHERE keycloak_id='kpi-owner';
            """);
        entityManager.clear();
        var owner = profiles.findByKeycloakId("kpi-owner").orElseThrow();
        var stationId = jdbc.queryForObject("SELECT id FROM stations WHERE name='KPI station'", java.util.UUID.class);
        when(currentProfile.requireProfile()).thenReturn(owner);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            "owner", "", List.of(new SimpleGrantedAuthority("ROLE_OWNER"))));

        var kpi = kpis.get(stationId, null, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(kpi.selfClaimedTickets()).isZero();
        assertThat(kpi.completedTickets()).isZero();
    }

    @Test
    void resolutionNoticeAndAutoCloseCommitOnceOnPostgres() {
        jdbc.execute("""
            INSERT INTO user_profile(keycloak_id,email,display_name,status)
            VALUES ('workflow-admin','workflow-admin@example.test','Admin','ACTIVE'),
                   ('workflow-driver','workflow-driver@example.test','Driver','ACTIVE')
            """);
        entityManager.clear();
        var admin = profiles.findByKeycloakId("workflow-admin").orElseThrow();
        var reporter = profiles.findByKeycloakId("workflow-driver").orElseThrow();
        var ticket = tickets.saveAndFlush(SupportTicket.open("TKT-20261001-9999", TicketCategory.PAYMENT,
            TicketPriority.MEDIUM, reporter, null, null,
            new SupportTicket.TicketDetails("Payment issue", "Wrong amount")));
        when(currentProfile.requireProfile()).thenReturn(admin);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
            "admin", "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        var claimed = workflow.claim(ticket.getId(), ticket.getVersion());
        var resolved = workflow.changeStatus(ticket.getId(),
            new TicketStatusRequest(claimed.version(), TicketStatus.RESOLVED, "Transaction reviewed"));
        assertThat(resolved.autoCloseAt()).isEqualTo(Instant.parse("2026-10-11T00:00:00Z"));
        assertThat(tickets.findDueFirst(resolved.autoCloseAt(), PageRequest.of(0, 100)))
            .extracting(SupportTicket::getId).contains(ticket.getId());
        assertThat(tickets.findDueAfter(resolved.autoCloseAt(), resolved.autoCloseAt(), ticket.getId(),
            PageRequest.of(0, 100))).extracting(SupportTicket::getId).doesNotContain(ticket.getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_notifications WHERE recipient_id = ?", Long.class, reporter.getId())).isEqualTo(1);
        assertThat(events.events(ticket.getId(), 1, 20).getTotalElements()).isEqualTo(2);

        when(currentProfile.requireProfile()).thenReturn(reporter);
        var notices = inbox.list(1, 20, true);
        assertThat(notices.getTotalElements()).isEqualTo(1);
        inbox.markRead(notices.getContent().getFirst().id());
        assertThat(inbox.unreadCount()).isZero();

        workflow.autoClose(ticket.getId(), resolved.autoCloseAt().minusNanos(1));
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getStatus()).isEqualTo(TicketStatus.RESOLVED);
        workflow.autoClose(ticket.getId(), resolved.autoCloseAt());
        workflow.autoClose(ticket.getId(), resolved.autoCloseAt());
        assertThat(tickets.findById(ticket.getId()).orElseThrow().getCloseReason()).isEqualTo("AUTO_CLOSED_NO_RESPONSE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_events WHERE ticket_id = ? AND event_type = 'AUTO_CLOSED_NO_RESPONSE'",
            Long.class, ticket.getId())).isEqualTo(1);
    }
}
