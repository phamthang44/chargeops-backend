package com.thang.chargeops.notification;

import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.AuthErrorCode;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.notification.entity.AppNotification;
import com.thang.chargeops.notification.model.*;
import com.thang.chargeops.notification.repository.AppNotificationRepository;
import com.thang.chargeops.notification.service.NotificationInboxService;
import com.thang.chargeops.notification.service.NotificationService;
import com.thang.chargeops.notification.service.NotificationPersistenceService;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.support.repository.SupportTicketRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@Testcontainers
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true", "spring.docker.compose.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({NotificationInboxService.class, NotificationService.class, NotificationPersistenceService.class,
        NotificationScopePostgresTest.Time.class})
class NotificationScopePostgresTest {
    static final Instant NOW = Instant.parse("2026-10-07T04:00:00Z");
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine").asCompatibleSubstituteFor("postgres"));
    @TestConfiguration static class Time { @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); } }
    @Autowired NotificationInboxService inbox;
    @Autowired NotificationService writer;
    @Autowired AppNotificationRepository notices;
    @Autowired UserProfileRepository users;
    @Autowired SupportTicketRepository tickets;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean CurrentProfileProvider profile;
    UUID owner, otherOwner, driver, otherDriver, staff, admin, station, otherStation, booking, otherBooking, ticket, escalation;

    @BeforeEach void seed() {
        owner = user(); otherOwner = user(); driver = user(); otherDriver = user(); staff = user(); admin = user();
        station = station(owner); otherStation = station(otherOwner);
        booking = booking(driver, station); otherBooking = booking(otherDriver, otherStation);
        ticket = UUID.randomUUID(); escalation = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO support_tickets(id,ticket_code,category,status,priority,reporter_id,station_id,booking_id,subject,description,resolution_cycle)
            VALUES (?,?,'BOOKING','OPEN','MEDIUM',?,?,?,'Support','Private evidence not in notice',1)
            """, ticket, "TKT-20261007-" + (10000 + Math.abs(ticket.hashCode() % 90000)), driver, station, booking);
        jdbc.update("INSERT INTO station_staff_assignments(station_id,user_id,assigned_by,status) VALUES (?,?,?,'ACTIVE')", station, staff, owner);
        jdbc.update("INSERT INTO ticket_escalations(id,ticket_id,requested_by,requested_at,reason) VALUES (?,?,?,?,'Review')",
                escalation, ticket, driver, java.sql.Timestamp.from(NOW.minusSeconds(100)));
        actor(driver, "DRIVER");
    }

    @AfterEach void clearSecurity() { SecurityContextHolder.clearContext(); }

    @Test void accountIsolationIncludesAdminAndAllMutations() {
        UUID own = notice(driver, "PERSONAL", "TICKET_RESOLVED", booking, ticket, station, null);
        UUID foreign = notice(otherDriver, "DRIVER", "BOOKING_CONFIRMED", otherBooking, null, otherStation, null);
        assertThat(inbox.list(1, 20, false).getContent()).extracting("id").containsExactly(own);
        assertThat(inbox.unreadCount()).isEqualTo(1);
        notFound(() -> inbox.markRead(foreign)); notFound(() -> inbox.dismiss(foreign, null, null, null));
        inbox.markAllRead();
        assertThat(readAt(foreign)).isNull();
        actor(admin, "ADMIN");
        assertThat(inbox.list(1, 20, false, "admin", null, null)).isEmpty();
        notFound(() -> inbox.markRead(own, "admin", null, null));
    }

    @Test void multiRoleViewsAndMarkAllStaySeparate() {
        // A person may own one station and drive at another, with separate notice audiences.
        UUID ownBooking = booking(owner, otherStation);
        UUID ownerNotice = notice(owner, "OWNER", "RESPONSIBILITY_DECIDED", booking, null, station, null);
        UUID driverNotice = notice(owner, "DRIVER", "BOOKING_CONFIRMED", ownBooking, null, otherStation, null);
        actor(owner, "OWNER", "DRIVER");
        assertThat(inbox.list(1, 20, false, "owner", null, null).getContent()).extracting("id").containsExactly(ownerNotice);
        assertThat(inbox.list(1, 20, false, "driver", null, null).getContent()).extracting("id").containsExactly(driverNotice);
        assertThat(inbox.list(1, 20, false)).isEmpty();
        inbox.markAllRead("owner", null, null);
        assertThat(readAt(ownerNotice)).isNotNull(); assertThat(readAt(driverNotice)).isNull();
        notFound(() -> inbox.markRead(ownerNotice, "driver", null, null));
    }

    @Test void contextRoleAndAccountStatusAreChecked() {
        assertThatThrownBy(() -> inbox.list(1, 20, false, "admin", null, null)).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.ACCESS_DENIED));
        assertThatThrownBy(() -> inbox.list(1, 20, false, "unknown", null, null)).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_REQUEST));
        jdbc.update("UPDATE user_profile SET status='SUSPENDED' WHERE id=?", driver); em.clear();
        assertThatThrownBy(inbox::unreadCount).isInstanceOfSatisfying(AppException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(AuthErrorCode.ACCESS_DENIED));
    }

    @Test void ownerStationAndFinancialSnapshotMustBothMatch() {
        notice(owner, "OWNER", "RESPONSIBILITY_DECIDED", booking, null, station, null);
        notice(owner, "OWNER", "RESPONSIBILITY_DECIDED", otherBooking, null, otherStation, null);
        notice(owner, "OWNER", "RESPONSIBILITY_DECIDED", otherBooking, null, station, null);
        actor(owner, "OWNER");
        assertThat(inbox.unreadCount("owner", "finance", null)).isEqualTo(1);
        assertThat(inbox.list(1, 20, false, "owner", null, otherStation)).isEmpty();
        assertThat(inbox.list(1, 20, false, "owner", null, station).getTotalElements()).isEqualTo(1);
    }

    @Test void staffRevocationRemovesReadCountAndMutationAccess() {
        UUID id = notice(staff, "STAFF", "TICKET_ASSIGNED", booking, ticket, station, null);
        actor(staff, "STAFF");
        assertThat(inbox.unreadCount("staff", null, null)).isEqualTo(1);
        jdbc.update("UPDATE station_staff_assignments SET status='REVOKED',revoked_at=?,revoked_by=? WHERE user_id=?", java.sql.Timestamp.from(NOW), owner, staff);
        assertThat(inbox.list(1, 20, false, "staff", null, null)).isEmpty();
        assertThat(inbox.unreadCount("staff", null, null)).isZero();
        notFound(() -> inbox.markRead(id, "staff", null, null));
        notFound(() -> inbox.dismiss(id, "staff", null, null));
        inbox.markAllRead("staff", null, null); assertThat(readAt(id)).isNull();
    }

    @Test void adminRequiresTheExactCaseNotAnyEscalation() {
        UUID permitted = notice(admin, "ADMIN", "CASE_ESCALATED", booking, ticket, station, escalation);
        UUID anotherTicket = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO support_tickets(id,ticket_code,category,status,priority,reporter_id,subject,description)
            VALUES (?,'TKT-20261007-999999','PAYMENT','OPEN','MEDIUM',?,'Other','Other')
            """, anotherTicket, driver);
        UUID wrong = notice(admin, "ADMIN", "CASE_ESCALATED", null, anotherTicket, null, escalation);
        actor(admin, "ADMIN");
        assertThat(inbox.list(1, 20, false, "admin", null, null).getContent()).extracting("id").containsExactly(permitted);
        notFound(() -> inbox.markRead(wrong, "admin", null, null));
    }

    @Test void typedResourceRelationsAreCheckedBeforePaginationAndCounts() {
        notice(driver, "PERSONAL", "TICKET_RESOLVED", otherBooking, ticket, station, null);
        UUID valid = notice(driver, "PERSONAL", "TICKET_RESOLVED", booking, ticket, station, null);
        assertThat(inbox.list(1, 1, false).getContent()).extracting("id").containsExactly(valid);
        assertThat(inbox.list(1, 1, false).getTotalElements()).isEqualTo(1);
        assertThat(inbox.unreadCount()).isEqualTo(1);
    }

    @Test void reporterNoticesWorkForOwnerAndAdminWithoutDriverRole() {
        for (UUID recipient : List.of(owner, admin)) {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO support_tickets(id,ticket_code,category,status,priority,reporter_id,subject,description,resolution_cycle)
                VALUES (?,?,'PAYMENT','OPEN','MEDIUM',?,'My report','Support',1)
                """, id, "TKT-20261007-" + (100000 + Math.abs(id.hashCode() % 900000)), recipient);
            writer.createTicketNotice(recipient, id + ":1:RESOLVED", "Resolved", "Safe reporter response", "/tickets/" + id, NOW.minusSeconds(1));
            actor(recipient, recipient.equals(owner) ? "OWNER" : "ADMIN");
            assertThat(inbox.list(1, 20, false).getContent()).singleElement().satisfies(n -> {
                assertThat(n.audience()).isEqualTo(NotificationAudience.PERSONAL);
                assertThat(n.target().ticketId()).isEqualTo(id);
            });
        }
    }

    @Test void dismissedExpiredAndQuarantinedRowsStayOutOfFeedAndCount() {
        UUID id = notice(driver, "DRIVER", "BOOKING_REMINDER", booking, null, station, null);
        jdbc.update("UPDATE app_notifications SET expires_at=? WHERE id=?", java.sql.Timestamp.from(NOW), id);
        UUID legacy = notice(driver, "PERSONAL", "LEGACY_QUARANTINED", null, null, null, null);
        UUID visible = notice(driver, "PERSONAL", "TICKET_RESOLVED", booking, ticket, station, null);
        assertThat(inbox.unreadCount("driver", null, null)).isEqualTo(1);
        inbox.dismiss(visible, "driver", null, null); inbox.dismiss(visible, "driver", null, null);
        assertThat(inbox.unreadCount("driver", null, null)).isZero();
        assertThat(inbox.list(1, 20, false, "driver", null, null)).isEmpty();
        notFound(() -> inbox.markRead(id, "driver", null, null)); notFound(() -> inbox.markRead(legacy));
    }

    @Test void categoryAndStationFiltersAlsoApplyToMarkAll() {
        UUID personal = notice(driver, "PERSONAL", "TICKET_RESOLVED", booking, ticket, station, null);
        UUID bookingNotice = notice(driver, "DRIVER", "BOOKING_CONFIRMED", booking, null, station, null);
        assertThat(inbox.list(1, 20, false, "driver", "booking", station).getTotalElements()).isEqualTo(1);
        inbox.markAllRead("driver", "booking", station);
        assertThat(readAt(bookingNotice)).isNotNull(); assertThat(readAt(personal)).isNull();
        assertThat(inbox.unreadCount("driver", "ticket", station)).isEqualTo(1);
    }

    @Test void markAllReadProcessesMultipleBatchesWithoutSkippingOrCrossingAccounts() {
        for (int i = 0; i < 205; i++) {
            notice(driver, "DRIVER", "BOOKING_CONFIRMED", booking, null, station, null);
        }
        UUID other = notice(otherDriver, "DRIVER", "BOOKING_CONFIRMED", otherBooking, null, otherStation, null);
        inbox.markAllRead("driver", null, null);
        assertThat(inbox.unreadCount("driver", null, null)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_notifications WHERE recipient_id=? AND read_at IS NOT NULL", Long.class, driver))
                .isEqualTo(205);
        assertThat(readAt(other)).isNull();
    }

    @Test void duplicatesDoNotResetReadOrDismissAndFanoutDoesNotConflict() {
        var original = AppNotification.resolvedTicket(tickets.findById(ticket).orElseThrow(), ticket + ":1:RESOLVED",
                new com.thang.chargeops.notification.model.NotificationContent("Resolved", "Safe", NOW.minusSeconds(1)));
        assertThat(writer.saveIfAbsent(original)).isTrue();
        UUID id = jdbc.queryForObject("SELECT id FROM app_notifications WHERE source_event_key=?", UUID.class, ticket + ":1:RESOLVED");
        inbox.markRead(id); inbox.markRead(id); inbox.dismiss(id, null, null, null);
        assertThat(writer.saveIfAbsent(original)).isFalse();
        assertThat(jdbc.queryForObject("SELECT dismissed_at IS NOT NULL AND read_at IS NOT NULL FROM app_notifications WHERE id=?", Boolean.class, id)).isTrue();
        UUID own = notice(driver, "DRIVER", "BOOKING_CONFIRMED", booking, null, station, null);
        UUID another = notice(otherDriver, "DRIVER", "BOOKING_CONFIRMED", otherBooking, null, otherStation, null);
        jdbc.update("UPDATE app_notifications SET source_event_key='shared-source' WHERE id IN (?,?)", own, another);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_notifications WHERE source_event_key='shared-source'", Long.class)).isEqualTo(2);
    }

    @Test void businessUpdateAndTicketNoticeRollBackTogether() {
        // Commit the initial OPEN ticket so we can inspect its state after rollback.
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();
        jdbc.update("UPDATE support_tickets SET status='RESOLVED' WHERE id=?", ticket);
        writer.createTicketNotice(driver, ticket + ":1:RESOLVED", "Resolved", "Safe", "/tickets/" + ticket, NOW);
        em.flush();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_notifications WHERE ticket_id=?", Long.class, ticket)).isEqualTo(1);
        TestTransaction.flagForRollback();
        TestTransaction.end();
        assertThat(jdbc.queryForObject("SELECT status FROM support_tickets WHERE id=?", String.class, ticket)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_notifications WHERE ticket_id=?", Long.class, ticket)).isZero();
    }

    @Test void producerRejectsWrongReporterAndUnsafeTarget() {
        assertThatThrownBy(() -> writer.createTicketNotice(otherDriver, ticket + ":1:RESOLVED", "Resolved", "Safe", "/tickets/" + ticket, NOW))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> writer.createTicketNotice(driver, ticket + ":1:RESOLVED", "Resolved", "Safe", "https://untrusted.test", NOW))
                .isInstanceOf(AppException.class);
    }

    @Test void equalTimestampsHaveStablePagesAndValidLimits() {
        for (int i = 0; i < 3; i++) notice(driver, "DRIVER", "BOOKING_CONFIRMED", booking, null, station, null);
        var first = inbox.list(1, 2, false, "driver", null, null);
        var second = inbox.list(2, 2, false, "driver", null, null);
        assertThat(first.getTotalElements()).isEqualTo(3); assertThat(second.getContent()).hasSize(1);
        assertThat(first.getContent()).extracting("id").doesNotContain(second.getContent().getFirst().id());
        assertThatThrownBy(() -> inbox.list(1, 101, false)).isInstanceOf(AppException.class);
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_profile(id,keycloak_id,email,status) VALUES (?,?,?,'ACTIVE')", id, id.toString(), id + "@example.test");
        return id;
    }
    private UUID station(UUID ownerId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO stations(id,owner_id,name,address_line,contact_phone,status) VALUES (?,?,?,'Test','000','ACTIVE')", id, ownerId, id.toString());
        return id;
    }
    private UUID booking(UUID driverId, UUID stationId) {
        UUID point = UUID.randomUUID(), connector = UUID.randomUUID(), id = UUID.randomUUID();
        String code = "N57" + id.toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
        jdbc.update("INSERT INTO charge_points(id,station_id,charge_point_code,provisioning_status,operational_status) VALUES (?,?,?,'ACTIVE','AVAILABLE')", point, stationId, code);
        jdbc.update("INSERT INTO connectors(id,charge_point_id,connector_code,connector_type,power_kw,charger_type,runtime_status) VALUES (?,?,?,'CCS2',60,'DC','AVAILABLE')", connector, point, code);
        jdbc.update("""
            INSERT INTO bookings(id,driver_id,connector_id,booking_code,start_at,end_at,status,total_amount,
                station_name_snapshot,station_address_snapshot,charge_point_code_snapshot,connector_code_snapshot,expires_at)
            VALUES (?,?,?,?,'2026-10-08 04:00Z','2026-10-08 05:00Z','PENDING',120000,'Test','Test',?,?,'2026-10-07 04:10Z')
            """, id, driverId, connector, code, code, code);
        return id;
    }
    private UUID notice(UUID recipient, String audience, String type, UUID bookingId, UUID ticketId, UUID stationId, UUID caseId) {
        UUID id = UUID.randomUUID();
        String category = type.equals("RESPONSIBILITY_DECIDED") ? "finance" : type.startsWith("BOOKING_") ? "booking" : "ticket";
        String action = caseId != null ? "OPEN_CASE" : ticketId != null ? "OPEN_TICKET" : bookingId != null ? "OPEN_BOOKING" : "NONE";
        jdbc.update("""
            INSERT INTO app_notifications(id,recipient_id,audience,event_type,category,event_key,source_event_key,booking_id,ticket_id,
                station_id,escalation_id,action_type,title,body,action_url,created_at,expires_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'Notice','Safe body','/safe',?,?)
            """, id, recipient, audience, type, category, id.toString(), id.toString(), bookingId, ticketId, stationId, caseId, action,
                java.sql.Timestamp.from(NOW.minusSeconds(1)), type.equals("BOOKING_REMINDER") ? java.sql.Timestamp.from(NOW.plusSeconds(600)) : null);
        return id;
    }
    private void actor(UUID id, String... roles) {
        em.flush();
        em.clear();
        when(profile.requireProfile()).thenAnswer(i -> users.findById(id).orElseThrow());
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(id.toString(), "",
                Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList()));
    }
    private Instant readAt(UUID id) {
        em.flush();
        return jdbc.queryForObject("SELECT read_at FROM app_notifications WHERE id=?",
                (rs, row) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).toInstant(), id);
    }
    private void notFound(ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
    }
}
