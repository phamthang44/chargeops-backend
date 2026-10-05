package com.thang.chargeops.booking;

import com.thang.chargeops.booking.command.BookingCommandInFlightLock;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.service.PaymentConfirmationService;
import com.thang.chargeops.payment.model.NormalizedReceipt;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.refund.service.AutomaticRefundExecutionService;
import com.thang.chargeops.refund.service.RefundObligationService;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.RefundErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "spring.docker.compose.enabled=false", "spring.task.scheduling.enabled=false",
        "app.refund.auto-execution.enabled=false"})
@AutoConfigureMockMvc @ActiveProfiles("test")
class OwnerCancellationPostgresTest {
    private static final Instant NOW = Instant.parse("2026-09-26T03:00:00Z");
    private static final Instant START_AT = NOW.plusSeconds(7200);
    private static final String REASON = "Trạm mất điện, không thể phục vụ đặt chỗ.";
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine").asCompatibleSubstituteFor("postgres"));
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired private UserProfileRepository userProfileRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentConfirmationService paymentConfirmationService;
    @Autowired private AutomaticRefundExecutionService automaticRefundExecutionService;
    @MockitoSpyBean private RefundObligationService obligations;
    @MockitoBean private CurrentProfileProvider currentProfileProvider;
    @MockitoBean private Clock applicationClock;
    @MockitoBean private BookingCommandInFlightLock bookingCommandInFlightLock;
    private final AtomicReference<UUID> currentActorId = new AtomicReference<>();
    private UUID ownerId;
    private UUID driverId;
    private UUID connectorId;

    @BeforeEach void setUp() {
        when(applicationClock.instant()).thenReturn(NOW);
        when(applicationClock.getZone()).thenReturn(ZoneOffset.UTC);
        when(applicationClock.withZone(any())).thenReturn(applicationClock);
        doAnswer(i -> ((Supplier<?>) i.getArgument(3)).get()).when(bookingCommandInFlightLock).executeWithLock(any(), any(), any(), any());
        when(currentProfileProvider.requireProfile()).thenAnswer(i -> userProfileRepository.findById(currentActorId.get()).orElseThrow());
        when(currentProfileProvider.requireProfileId()).thenAnswer(i -> currentActorId.get());
        Fixture fixture = insertBookableStationFixture();
        driverId = fixture.driverId(); connectorId = fixture.connectorId();
        ownerId = jdbc.queryForObject("SELECT s.owner_id FROM stations s JOIN charge_points cp ON cp.station_id=s.id JOIN connectors c ON c.charge_point_id=cp.id WHERE c.id=?", UUID.class, connectorId);
        currentActorId.set(driverId);
    }

    @Test void adminGrantCancelsAndReplaysAfterCaseClosure() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        PolicyCase policyCase = createPolicyCase(paid.bookingId());
        UUID key = UUID.randomUUID();
        JsonNode granted = adminReview(policyCase, key, policyPayload(1, 0, true), 200);
        assertThat(granted.at("/data/decision/actorKind").asText()).isEqualTo("ADMIN");
        assertThat(granted.at("/data/refund/amount").asLong()).isEqualTo(paid.amount());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_status_history WHERE booking_id=? AND actor_type='ADMIN' AND reason='ADMIN_POLICY_CANCELLED'", Long.class, paid.bookingId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT status FROM support_tickets WHERE id=?", String.class, policyCase.ticketId())).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT resolved_at FROM ticket_escalations WHERE id=?", Timestamp.class, policyCase.escalationId())).isNull();
        jdbc.update("UPDATE support_tickets SET status='CLOSED', version=version+1 WHERE id=?", policyCase.ticketId());
        assertThat(adminReview(policyCase, key, policyPayload(1, 0, true), 200).at("/data/decision/decisionId"))
                .isEqualTo(granted.at("/data/decision/decisionId"));
        adminReview(policyCase, UUID.randomUUID(), policyPayload(2, 1, true), 409);
        mockMvc.perform(get(policyUrl(policyCase) + "/refund-policy-context").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.grant.allowed").value(false))
                .andExpect(jsonPath("$.data.insufficient.allowed").value(false));
        assertCount("refunds", paid.bookingId(), 1);
    }

    @Test void incidentStaffReportSnapshotsAndBlocksNewBookingsWithoutGrantingRefund() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        UUID stationId = incidentStationId();
        UUID staffId = UUID.randomUUID();
        insertUser(staffId, "incident-staff-" + staffId, "Incident Staff");
        jdbc.update("INSERT INTO station_staff_assignments(id,station_id,user_id,assigned_by,status,assigned_at,version) VALUES (?,?,?,?, 'ACTIVE',?,0)",
                UUID.randomUUID(), stationId, staffId, ownerId, Timestamp.from(NOW));
        currentActorId.set(staffId);
        UUID key = UUID.randomUUID();
        JsonNode reported = incidentReport(key, 0, "driver", 200);
        UUID incidentId = UUID.fromString(reported.at("/data/incidentId").asText());
        assertThat(reported.at("/data/runtimeStatus").asText()).isEqualTo("OFFLINE");
        assertThat(reported.at("/data/affectedBookingIds/0").asText()).isEqualTo(paid.bookingId().toString());
        assertThat(reported.toString()).doesNotContain("paymentId", "receiptId", "amount", "payloadHash", "keycloakId");
        assertThat(incidentReport(key, 0, "driver", 200).at("/data/incidentId")).isEqualTo(reported.at("/data/incidentId"));
        incidentReport(key, 1, "driver", 409);
        assertCount("refunds", paid.bookingId(), 0);
        assertThat(jdbc.queryForObject("SELECT actor_type FROM connector_incidents WHERE id=?", String.class, incidentId)).isEqualTo("STAFF");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incident_commands WHERE incident_id=?", Long.class, incidentId)).isEqualTo(1L);
        mockMvc.perform(post("/api/v1/bookings/price-preview").with(user("driver").roles("DRIVER"))
                .contentType("application/json").content(json(Map.of("connectorId",connectorId,"startAt",START_AT.plusSeconds(7200),"durationMin",60))))
                .andExpect(status().isConflict());
        jdbc.update("UPDATE station_staff_assignments SET status='REVOKED',revoked_by=?,revoked_at=? WHERE user_id=?",ownerId,Timestamp.from(NOW),staffId);
        mockMvc.perform(get("/api/v1/stations/{s}/incidents/{i}",stationId,incidentId).with(user("staff").roles("DRIVER")))
                .andExpect(status().isForbidden());
    }

    @Test void incidentSafeStopThenOwnerRefundRecoveryPreservesHistoryAndPendingObligation() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        activateIncidentSession(paid.bookingId(), "CHARGING");
        currentActorId.set(ownerId);
        JsonNode reported = incidentReport(UUID.randomUUID(), 0, "owner", 200);
        UUID incidentId = UUID.fromString(reported.at("/data/incidentId").asText());
        assertThat(reported.at("/data/handlingState").asText()).isEqualTo("PENDING");
        UUID key = UUID.randomUUID();
        JsonNode resolved = incidentResolve(incidentId,paid.bookingId(),1,key,true,200);
        assertThat(resolved.at("/data/handlingState").asText()).isEqualTo("COMPLETED");
        assertThat(resolved.at("/data/runtimeStatus").asText()).isEqualTo("OFFLINE");
        assertThat(resolved.at("/data/affectedBookings/0/currentStatus").asText()).isEqualTo("COMPLETED");
        incidentResolve(incidentId,paid.bookingId(),1,key,true,200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_status_history WHERE booking_id=? AND actor_type='OWNER' AND reason='INCIDENT_SESSION_STOPPED'",Long.class,paid.bookingId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT end_at FROM bookings WHERE id=?",Timestamp.class,paid.bookingId()).toInstant()).isEqualTo(START_AT.plusSeconds(3600));
        assertCount("refunds",paid.bookingId(),0);
        mockMvc.perform(post("/api/v1/owner/bookings/{id}/station-failure-refund",paid.bookingId()).with(user("owner").roles("OWNER"))
                .header("Idempotency-Key",UUID.randomUUID()).contentType("application/json")
                .content(json(Map.of("expectedVersion",2,"expectedDecisionVersion",0,"affectedAt",START_AT.plusSeconds(60),"reason",REASON + " Incident " + incidentId))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.refund.status").value("PENDING"));
        JsonNode recovered = incidentRecover(incidentId,0,UUID.randomUUID(),200);
        assertThat(recovered.at("/data/runtimeStatus").asText()).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT status FROM refunds WHERE booking_id=?",String.class,paid.bookingId())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,paid.bookingId())).isEqualTo("COMPLETED");
        assertCount("refunds",paid.bookingId(),1);
        JsonNode evidence = objectMapper.readTree(jdbc.queryForObject("SELECT evidence_snapshot::text FROM booking_service_failure_decisions WHERE booking_id=?",String.class,paid.bookingId()));
        assertThat(evidence.at("/incidents/0/incidentId").asText()).isEqualTo(incidentId.toString());
        assertThat(evidence.at("/incidents/0/safetyState").asText()).isEqualTo("SESSION_RESOLVED");
    }

    @Test void incidentTwoOpenIncidentsRecoveryAndOrdinaryRuntimeGuard() throws Exception {
        currentActorId.set(ownerId);
        UUID first = UUID.fromString(incidentReport(UUID.randomUUID(),0,"owner",200).at("/data/incidentId").asText());
        UUID second = UUID.fromString(incidentReport(UUID.randomUUID(),1,"owner",200).at("/data/incidentId").asText());
        UUID key = UUID.randomUUID();
        assertThat(incidentRecover(first,0,key,200).at("/data/runtimeStatus").asText()).isEqualTo("OFFLINE");
        UUID cpId = jdbc.queryForObject("SELECT charge_point_id FROM connectors WHERE id=?",UUID.class,connectorId);
        mockMvc.perform(patch("/api/v1/owner/stations/{s}/charge-points/{cp}/connectors/{c}/runtime-status",incidentStationId(),cpId,connectorId)
                .with(user("owner").roles("OWNER")).contentType("application/json").content(json(Map.of("runtimeStatus","AVAILABLE","reason",REASON))))
                .andExpect(status().isConflict());
        assertThat(incidentRecover(first,0,key,200).at("/data/status").asText()).isEqualTo("RECOVERED");
        incidentRecover(first,0,UUID.randomUUID(),409);
        assertThat(incidentRecover(second,0,UUID.randomUUID(),200).at("/data/runtimeStatus").asText()).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM connector_incidents WHERE connector_id=?",Long.class,connectorId)).isEqualTo(2L);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM connector_incidents WHERE id=?",first)).hasMessageContaining("cannot be deleted");
        assertThatThrownBy(() -> jdbc.update("UPDATE connector_incidents SET reason='rewritten evidence' WHERE id=?",first)).hasMessageContaining("immutable");
    }

    @Test void incidentRecoveryRetainsInUseForActiveSessionAndOfflineForMaintenance() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        activateIncidentSession(paid.bookingId(),"CHECKED_IN");
        currentActorId.set(ownerId);
        UUID first = UUID.fromString(incidentReport(UUID.randomUUID(),0,"owner",200).at("/data/incidentId").asText());
        assertThat(incidentRecover(first,0,UUID.randomUUID(),200).at("/data/runtimeStatus").asText()).isEqualTo("IN_USE");
        incidentResolve(first,paid.bookingId(),1,UUID.randomUUID(),true,200);
        UUID second = UUID.fromString(incidentReport(UUID.randomUUID(),3,"owner",200).at("/data/incidentId").asText());
        jdbc.update("UPDATE charge_points SET operational_status='MAINTENANCE' WHERE id=(SELECT charge_point_id FROM connectors WHERE id=?)",connectorId);
        assertThat(incidentRecover(second,0,UUID.randomUUID(),200).at("/data/runtimeStatus").asText()).isEqualTo("OFFLINE");
    }

    @Test void incidentRejectsStaleVersionWrongScopeFutureTimeUnknownMoneyAndUnsafeStop() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        activateIncidentSession(paid.bookingId(),"CHARGING");
        currentActorId.set(ownerId);
        incidentReport(UUID.randomUUID(),99,"owner",409);
        mockMvc.perform(post("/api/v1/stations/{s}/connectors/{c}/incidents",incidentStationId(),connectorId)
                .with(user("owner").roles("OWNER")).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json")
                .content(json(Map.of("expectedConnectorVersion",0,"reason",REASON,"occurredAt",START_AT.plusSeconds(61)))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/stations/{s}/connectors/{c}/incidents",incidentStationId(),connectorId)
                .with(user("owner").roles("OWNER")).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json")
                .content(json(Map.of("expectedConnectorVersion",0,"reason",REASON,"occurredAt",START_AT,"amount",100))))
                .andExpect(status().isBadRequest());
        UUID incidentId = UUID.fromString(incidentReport(UUID.randomUUID(),0,"owner",200).at("/data/incidentId").asText());
        incidentResolve(incidentId,paid.bookingId(),1,UUID.randomUUID(),false,400);
        incidentResolve(incidentId,paid.bookingId(),99,UUID.randomUUID(),true,409);
        mockMvc.perform(get("/api/v1/stations/{s}/incidents/{i}",UUID.randomUUID(),incidentId).with(user("owner").roles("OWNER")))
                .andExpect(status().isNotFound());
        currentActorId.set(driverId);
        mockMvc.perform(get("/api/v1/stations/{s}/incidents/{i}",incidentStationId(),incidentId).with(user("driver").roles("DRIVER")))
                .andExpect(status().isForbidden());
        assertCount("refunds",paid.bookingId(),0);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,paid.bookingId())).isEqualTo("CHARGING");
    }

    @Test void incidentSnapshotFailureRollsBackOfflineAndCommand() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        currentActorId.set(ownerId);
        UUID key = UUID.randomUUID();
        jdbc.execute("CREATE FUNCTION fail_incident_snapshot_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Injected snapshot crash'; END $$");
        jdbc.execute("CREATE TRIGGER fail_incident_snapshot_test BEFORE INSERT ON incident_bookings FOR EACH ROW EXECUTE FUNCTION fail_incident_snapshot_test()");
        try {
            incidentReport(key,0,"owner",500);
            assertThat(jdbc.queryForObject("SELECT runtime_status FROM connectors WHERE id=?",String.class,connectorId)).isEqualTo("AVAILABLE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM connector_incidents WHERE connector_id=?",Long.class,connectorId)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER fail_incident_snapshot_test ON incident_bookings");
            jdbc.execute("DROP FUNCTION fail_incident_snapshot_test()");
        }
        incidentReport(key,0,"owner",200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incident_bookings WHERE booking_id=?",Long.class,paid.bookingId())).isEqualTo(1L);
    }

    private UUID incidentStationId() {
        return jdbc.queryForObject("SELECT cp.station_id FROM charge_points cp JOIN connectors c ON c.charge_point_id=cp.id WHERE c.id=?",UUID.class,connectorId);
    }

    @Test void incidentUnpaidOwnerCancellationAndRecoveryNeverResurrectBooking() throws Exception {
        PendingBooking pending = createPendingBookingWithCheckout(START_AT);
        currentActorId.set(ownerId);
        UUID incidentId = UUID.fromString(incidentReport(UUID.randomUUID(),0,"owner",200).at("/data/incidentId").asText());
        ownerCancel(pending.bookingId(),0,UUID.randomUUID(),REASON,200);
        incidentRecover(incidentId,0,UUID.randomUUID(),200);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,pending.bookingId())).isEqualTo("CANCELLED");
        assertCount("refunds",pending.bookingId(),0);
    }

    @Test void incidentSnapshotIncludesMultipleBookingsAndCannotBeRewritten() throws Exception {
        PaidBooking first = createAndPayBooking(START_AT);
        PaidBooking second = createAndPayBooking(START_AT.plusSeconds(7200));
        currentActorId.set(ownerId);
        JsonNode response = incidentReport(UUID.randomUUID(),0,"owner",200);
        assertThat(response.at("/data/affectedBookingIds").size()).isEqualTo(2);
        UUID incidentId = UUID.fromString(response.at("/data/incidentId").asText());
        ownerCancel(first.bookingId(),1,UUID.randomUUID(),REASON,200);
        incidentRecover(incidentId,0,UUID.randomUUID(),200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incident_bookings WHERE incident_id=?",Long.class,incidentId)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,second.bookingId())).isEqualTo("CONFIRMED");
        assertThatThrownBy(() -> jdbc.update("UPDATE incident_bookings SET snapshot_version=99 WHERE incident_id=?",incidentId))
                .hasMessageContaining("immutable");
    }

    @Test void incidentAdminOperationalAuthorityDoesNotCreateFinancialDecision() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        UUID adminId = UUID.randomUUID();
        insertUser(adminId,"incident-admin-" + adminId,"Incident Admin");
        currentActorId.set(adminId);
        JsonNode reported = body(mockMvc.perform(post("/api/v1/stations/{s}/connectors/{c}/incidents",incidentStationId(),connectorId)
                .with(user("admin").roles("ADMIN")).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json")
                .content(json(Map.of("expectedConnectorVersion",0,"reason",REASON,"occurredAt",NOW))))
                .andExpect(status().isOk()).andReturn());
        UUID incidentId = UUID.fromString(reported.at("/data/incidentId").asText());
        assertThat(jdbc.queryForObject("SELECT actor_type FROM connector_incidents WHERE id=?",String.class,incidentId)).isEqualTo("ADMIN");
        assertCount("refunds",paid.bookingId(),0);
        assertCount("booking_service_failure_decisions",paid.bookingId(),0);
        jdbc.update("UPDATE user_profile SET status='SUSPENDED' WHERE id=?",adminId);
        mockMvc.perform(get("/api/v1/stations/{s}/incidents/{i}",incidentStationId(),incidentId).with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test void incidentConcurrentReportSameKeyCreatesOneSnapshotAndOneCommand() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        currentActorId.set(ownerId);
        UUID key = UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<JsonNode> call = () -> { ready.countDown(); start.await(10,TimeUnit.SECONDS); return incidentReport(key,0,"owner",200); };
            Future<JsonNode> a = pool.submit(call), b = pool.submit(call);
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(a.get(30,TimeUnit.SECONDS).at("/data/incidentId")).isEqualTo(b.get(30,TimeUnit.SECONDS).at("/data/incidentId"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM connector_incidents WHERE connector_id=?",Long.class,connectorId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM incident_bookings WHERE booking_id=?",Long.class,paid.bookingId())).isEqualTo(1L);
    }

    @Test void incidentRecoveryWaitingOnConnectorSeesNewIncidentAndKeepsOffline() throws Exception {
        currentActorId.set(ownerId);
        UUID first = UUID.fromString(incidentReport(UUID.randomUUID(),0,"owner",200).at("/data/incidentId").asText());
        UUID otherOwner = UUID.randomUUID();
        insertUser(otherOwner,"incident-race-" + otherOwner,"Incident race admin");
        AtomicReference<Future<Integer>> response = new AtomicReference<>();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT version FROM connectors WHERE id=? FOR UPDATE",Long.class,connectorId);
                response.set(executor.submit(() -> mockMvc.perform(post("/api/v1/stations/{s}/incidents/{i}/recover",incidentStationId(),first)
                        .with(user("owner").roles("OWNER")).header("Idempotency-Key",UUID.randomUUID()).contentType("application/json")
                        .content(json(Map.of("expectedVersion",0,"reason",REASON)))).andReturn().getResponse().getStatus()));
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() ->
                        jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%connectors%'",Long.class) > 0);
                jdbc.update("INSERT INTO connector_incidents(id,connector_id,status,reason,occurred_at,reported_at,reported_by,actor_type,previous_runtime_status) VALUES (?,?,'OPEN',?,?,?,?,'ADMIN','OFFLINE')",
                        UUID.randomUUID(),connectorId,REASON,Timestamp.from(NOW),Timestamp.from(NOW),otherOwner);
            });
            assertThat(response.get().get(30,TimeUnit.SECONDS)).isEqualTo(200);
        }
        assertThat(jdbc.queryForObject("SELECT runtime_status FROM connectors WHERE id=?",String.class,connectorId)).isEqualTo("OFFLINE");
    }
    private JsonNode incidentReport(UUID key,long version,String role,int expectedStatus) throws Exception {
        return body(mockMvc.perform(post("/api/v1/stations/{s}/connectors/{c}/incidents",incidentStationId(),connectorId)
                .with(user(role).roles(role.equals("owner") ? "OWNER" : "DRIVER"))
                .header("Idempotency-Key",key).contentType("application/json")
                .content(json(Map.of("expectedConnectorVersion",version,"reason",REASON,"occurredAt",NOW))))
                .andExpect(status().is(expectedStatus)).andReturn());
    }
    private JsonNode incidentRecover(UUID incidentId,long version,UUID key,int expectedStatus) throws Exception {
        return body(mockMvc.perform(post("/api/v1/stations/{s}/incidents/{i}/recover",incidentStationId(),incidentId)
                .with(user("owner").roles("OWNER")).header("Idempotency-Key",key).contentType("application/json")
                .content(json(Map.of("expectedVersion",version,"reason",REASON))))
                .andExpect(status().is(expectedStatus)).andReturn());
    }
    private JsonNode incidentResolve(UUID incidentId,UUID bookingId,long version,UUID key,boolean confirmed,int expectedStatus) throws Exception {
        return body(mockMvc.perform(post("/api/v1/stations/{s}/incidents/{i}/bookings/{b}/resolve-session",incidentStationId(),incidentId,bookingId)
                .with(user("owner").roles("OWNER")).header("Idempotency-Key",key).contentType("application/json")
                .content(json(Map.of("expectedBookingVersion",version,"reason",REASON,"safetyConfirmed",confirmed))))
                .andExpect(status().is(expectedStatus)).andReturn());
    }
    private void activateIncidentSession(UUID bookingId,String status) {
        jdbc.update("UPDATE bookings SET status=?,checked_in_at=?,charging_started_at=? WHERE id=?",status,Timestamp.from(START_AT),
                status.equals("CHARGING") ? Timestamp.from(START_AT.plusSeconds(30)) : null,bookingId);
        jdbc.update("UPDATE connectors SET runtime_status='IN_USE' WHERE id=?",connectorId);
        when(applicationClock.instant()).thenReturn(START_AT.plusSeconds(60));
    }

    @Test void adminInsufficientDoesNotPreventOwnerAdmissionAndReplayShowsCurrentRefund() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        jdbc.update("UPDATE bookings SET status='COMPLETED' WHERE id=?", paid.bookingId());
        PolicyCase policyCase = createPolicyCase(paid.bookingId());
        UUID key = UUID.randomUUID();
        JsonNode insufficient = adminReview(policyCase, key, policyPayload(1, 0, false), 200);
        assertThat(insufficient.at("/data/decision/result").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertCount("refunds", paid.bookingId(), 0);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?", String.class, paid.bookingId())).isEqualTo("COMPLETED");
        currentActorId.set(ownerId);
        mockMvc.perform(post("/api/v1/owner/bookings/{id}/station-failure-refund", paid.bookingId())
                .with(user("owner").roles("OWNER")).header("Idempotency-Key", UUID.randomUUID())
                .contentType("application/json").content(json(Map.of("expectedVersion", 1, "expectedDecisionVersion", 1,
                        "affectedAt", NOW.toString(), "reason", REASON)))).andExpect(status().isOk());
        currentActorId.set(policyCase.adminId());
        JsonNode replay = adminReview(policyCase, key, policyPayload(1, 0, false), 200);
        assertThat(replay.at("/data/decision/result").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(replay.at("/data/refund/amount").asLong()).isEqualTo(paid.amount());
        assertThat(adminReview(policyCase, UUID.randomUUID(), policyPayload(1, 2, false), 409).at("/error/code").asText())
                .isEqualTo("RFD_REFUND_ALREADY_GRANTED");
    }

    @Test void adminLinksExistingRefundWithoutNewDispatchOrChangingBasis() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        currentActorId.set(ownerId);
        JsonNode ownerGrant = ownerCancel(paid.bookingId(), 1, UUID.randomUUID(), REASON, 200);
        PolicyCase policyCase = createPolicyCase(paid.bookingId());
        JsonNode linked = adminReview(policyCase, UUID.randomUUID(), policyPayload(2, 1, true), 200);
        assertThat(linked.at("/data/refund/refundId")).isEqualTo(ownerGrant.at("/data/refund/refundId"));
        UUID refundId = UUID.fromString(linked.at("/data/refund/refundId").asText());
        assertThat(jdbc.queryForObject("SELECT basis_type FROM refunds WHERE id=?", String.class, refundId)).isEqualTo("BOOKING_CANCELLATION");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refund_auto_dispatches WHERE refund_id=?", Long.class, refundId)).isEqualTo(1L);
        assertCount("refunds", paid.bookingId(), 1);
    }

    @Test void adminGrantReconcilesNoShowAndSnapshotsInputState() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        PolicyCase policyCase = createPolicyCase(paid.bookingId());
        Instant deadline = jdbc.queryForObject("SELECT check_in_deadline FROM bookings WHERE id=?", Timestamp.class, paid.bookingId()).toInstant();
        when(applicationClock.instant()).thenReturn(deadline);
        adminReview(policyCase, UUID.randomUUID(), policyPayload(1, 0, true), 200);
        assertThat(jdbc.queryForObject("SELECT cancellation_reason FROM bookings WHERE id=?", String.class, paid.bookingId())).isEqualTo("NO_SHOW");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_status_history WHERE booking_id=? AND actor_type='SYSTEM' AND reason='NO_SHOW'", Long.class, paid.bookingId())).isEqualTo(1L);
        JsonNode snapshot = objectMapper.readTree(jdbc.queryForObject("SELECT evidence_snapshot::text FROM booking_service_failure_decisions WHERE booking_id=?", String.class, paid.bookingId()));
        assertThat(snapshot.path("persistedStatus").asText()).isEqualTo("CONFIRMED");
        assertThat(snapshot.path("bookingVersion").asLong()).isEqualTo(1);
    }

    @Test void adminPolicyRejectsWrongCaseStaleVersionsAndUnknownAmount() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        PolicyCase policyCase = createPolicyCase(paid.bookingId());
        PolicyCase wrongCase = new PolicyCase(policyCase.ticketId(), UUID.randomUUID(), policyCase.adminId());
        adminReview(wrongCase, UUID.randomUUID(), policyPayload(1, 0, true), 404);
        adminReview(policyCase, UUID.randomUUID(), policyPayload(0, 0, true), 409);
        adminReview(policyCase, UUID.randomUUID(), policyPayload(1, 1, true), 409);
        Map<String, Object> payload = new LinkedHashMap<>(policyPayload(1, 0, true));
        payload.put("amount", paid.amount());
        adminReview(policyCase, UUID.randomUUID(), payload, 400);
        for (String role : List.of("OWNER", "STAFF", "DRIVER")) {
            mockMvc.perform(post(policyUrl(policyCase) + "/refund-policy-decisions").with(user("other").roles(role))
                    .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json")
                    .content(json(policyPayload(1, 0, true)))).andExpect(status().isForbidden());
        }
        assertCount("booking_service_failure_decisions", paid.bookingId(), 0);
    }

    @Test void adminContextHidesOtherCaseDecisionsAndNeverOffersOwnerActions() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        PolicyCase first = createPolicyCase(paid.bookingId());
        adminReview(first, UUID.randomUUID(), policyPayload(1, 0, false), 200);
        PolicyCase second = createPolicyCase(paid.bookingId());
        mockMvc.perform(get(policyUrl(second) + "/refund-policy-context").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.booking.decisionVersion").value(1))
                .andExpect(jsonPath("$.data.booking.serviceFailureDecisions").isEmpty())
                .andExpect(jsonPath("$.data.booking.cancel.allowed").value(false))
                .andExpect(jsonPath("$.data.booking.acceptResponsibility.allowed").value(false));
    }

    @Test void adminAndOwnerConcurrentGrantCreateOneObligation() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        PolicyCase policyCase = createPolicyCase(paid.bookingId());
        when(currentProfileProvider.requireProfile()).thenAnswer(i -> {
            var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
            return userProfileRepository.findById(admin ? policyCase.adminId() : ownerId).orElseThrow();
        });
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> owner = pool.submit(() -> concurrentCancel(paid, UUID.randomUUID(), ready, start));
            Future<Integer> admin = pool.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                return mockMvc.perform(post(policyUrl(policyCase) + "/refund-policy-decisions").with(user("admin").roles("ADMIN"))
                        .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json")
                        .content(json(policyPayload(1, 0, true)))).andReturn().getResponse().getStatus();
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(owner.get(30, TimeUnit.SECONDS), admin.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertCount("refunds", paid.bookingId(), 1);
        assertCount("booking_service_failure_decisions", paid.bookingId(), 1);
    }

    @Test void adminCannotGrantWhenCaseClosesWhileWaitingForFinancialLock() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        PolicyCase policyCase = createPolicyCase(paid.bookingId());
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        try (var pool = Executors.newSingleThreadExecutor()) {
            AtomicReference<Future<Integer>> response = new AtomicReference<>();
            transaction.executeWithoutResult(state -> {
                jdbc.queryForObject("SELECT id FROM connectors WHERE id=? FOR UPDATE", UUID.class, connectorId);
                response.set(pool.submit(() -> mockMvc.perform(post(policyUrl(policyCase) + "/refund-policy-decisions")
                        .with(user("admin").roles("ADMIN")).header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json").content(json(policyPayload(1, 0, true))))
                        .andReturn().getResponse().getStatus()));
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() ->
                        jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%connectors%'", Long.class) > 0);
                jdbc.update("UPDATE support_tickets SET status='CLOSED', version=version+1 WHERE id=?", policyCase.ticketId());
            });
            assertThat(response.get().get(30, TimeUnit.SECONDS)).isEqualTo(409);
        }
        assertCount("refunds", paid.bookingId(), 0);
        assertCount("booking_service_failure_decisions", paid.bookingId(), 0);
    }

    private PolicyCase createPolicyCase(UUID bookingId) {
        UUID adminId = UUID.randomUUID(), ticketId = UUID.randomUUID(), escalationId = UUID.randomUUID();
        String suffix = adminId.toString().replace("-", "");
        insertUser(adminId, "admin-policy-" + suffix, "Policy admin");
        UUID stationId = jdbc.queryForObject("SELECT cp.station_id FROM charge_points cp JOIN connectors c ON c.charge_point_id=cp.id WHERE c.id=?", UUID.class, connectorId);
        jdbc.update("""
                INSERT INTO support_tickets(id,ticket_code,category,status,priority,reporter_id,station_id,booking_id,subject,description)
                VALUES (?, 'TKT-20260926-' || lpad(nextval('support_ticket_code_seq')::text, 4, '0'), 'CHARGING_ISSUE','OPEN','MEDIUM',?,?,?,'Station failure','Investigate station failure')
                """, ticketId, driverId, stationId, bookingId);
        jdbc.update("INSERT INTO ticket_escalations(id,ticket_id,requested_by,requested_at,reason) VALUES (?,?,?,?,?)",
                escalationId, ticketId, driverId, Timestamp.from(NOW), REASON);
        currentActorId.set(adminId);
        return new PolicyCase(ticketId, escalationId, adminId);
    }

    private Map<String, Object> policyPayload(long bookingVersion, long decisionVersion, boolean grant) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("expectedVersion", 0);
        payload.put("expectedBookingVersion", bookingVersion);
        payload.put("expectedDecisionVersion", decisionVersion);
        payload.put("outcome", grant ? "GRANT_FULL_REFUND" : "INSUFFICIENT_EVIDENCE");
        payload.put("reason", REASON);
        if (grant) payload.put("affectedAt", NOW.toString());
        return payload;
    }

    private JsonNode adminReview(PolicyCase policyCase, UUID key, Map<String, Object> payload, int expectedStatus) throws Exception {
        return body(mockMvc.perform(post(policyUrl(policyCase) + "/refund-policy-decisions").with(user("admin").roles("ADMIN"))
                .header("Idempotency-Key", key).contentType("application/json").content(json(payload)))
                .andExpect(status().is(expectedStatus)).andReturn());
    }

    private String policyUrl(PolicyCase policyCase) {
        return "/api/v1/admin/tickets/" + policyCase.ticketId() + "/escalations/" + policyCase.escalationId();
    }

    private record PolicyCase(UUID ticketId, UUID escalationId, UUID adminId) {}

    @Test void unpaidCancelRecordsReasonAndNoRefundAndReplay() throws Exception {
        PendingBooking pending = createPendingBookingWithCheckout(START_AT);
        currentActorId.set(ownerId); UUID key = UUID.randomUUID();
        JsonNode result = ownerCancel(pending.bookingId(), 0, key, "  " + REASON + "  ", 200);
        assertThat(result.at("/data/decision/result").asText()).isEqualTo("NO_APPLIED_PAYMENT");
        assertThat(result.at("/data/decision/reason").asText()).isEqualTo(REASON);
        assertThat(result.at("/data/refund").isNull() || result.at("/data/refund").isMissingNode()).isTrue();
        assertThat(ownerCancel(pending.bookingId(), 0, key, REASON, 200).at("/data/decision/decisionId")).isEqualTo(result.at("/data/decision/decisionId"));
        assertCount("refunds", pending.bookingId(), 0); assertCount("booking_service_failure_decisions", pending.bookingId(), 1);
        assertOwnerHistory(pending.bookingId());
        assertThat(jdbc.queryForObject("SELECT runtime_status FROM connectors WHERE id=?", String.class, connectorId)).isEqualTo("AVAILABLE");
        mockMvc.perform(get("/api/v1/owner/bookings/{id}", pending.bookingId()).with(user("owner").roles("OWNER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.serviceFailureDecisions[0].reason").value(REASON));
    }

    @Test void fullPackageAfterGraceAndReplayTracksSucceededRefund() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        when(applicationClock.instant()).thenReturn(NOW.plusSeconds(120)); currentActorId.set(ownerId);
        UUID key = UUID.randomUUID(); JsonNode result = ownerCancel(paid.bookingId(), 1, key, REASON, 200);
        assertThat(result.at("/data/refund/amount").asLong()).isEqualTo(paid.amount());
        assertThat(result.at("/data/refund/status").asText()).isEqualTo("PENDING");
        UUID refundId = UUID.fromString(result.at("/data/refund/refundId").asText());
        assertThat(jdbc.queryForObject("SELECT basis_type FROM refunds WHERE id=?", String.class, refundId)).isEqualTo("BOOKING_CANCELLATION");
        assertThat(jdbc.queryForObject("SELECT reason FROM refunds WHERE id=?", String.class, refundId)).isEqualTo("STATION_FAILURE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refund_auto_dispatches WHERE refund_id=?", Long.class, refundId)).isEqualTo(1L);
        assertThat(automaticRefundExecutionService.processFirstAttempt(refundId)).isTrue();
        assertThat(ownerCancel(paid.bookingId(), 1, key, REASON, 200).at("/data/refund/status").asText()).isEqualTo("SUCCEEDED");
        assertCount("refunds", paid.bookingId(), 1); assertOwnerHistory(paid.bookingId());
        assertThat(jdbc.queryForObject("SELECT refund_amount FROM payments WHERE id=?", java.math.BigDecimal.class, paid.paymentId()).longValueExact()).isEqualTo(paid.amount());
        currentActorId.set(driverId);
        mockMvc.perform(get("/api/v1/bookings/{id}", paid.bookingId()).with(user("driver").roles("DRIVER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.serviceFailureDecisions[0].reason").value(REASON))
                .andExpect(jsonPath("$.data.serviceFailureDecisions[0].actorProfileId").doesNotExist())
                .andExpect(jsonPath("$.data.serviceFailureDecisions[0].evidenceSnapshot").doesNotExist());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void concurrentCommandsCreateOneDecisionAndRefund(boolean sameKey) throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT); currentActorId.set(ownerId);
        UUID key = UUID.randomUUID(); CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> a = pool.submit(() -> concurrentCancel(paid, key, ready, start));
            Future<Integer> b = pool.submit(() -> concurrentCancel(paid, sameKey ? key : UUID.randomUUID(), ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, sameKey ? 200 : 409);
        }
        assertCount("refunds", paid.bookingId(), 1); assertCount("booking_service_failure_decisions", paid.bookingId(), 1);
    }

    @Test void wrongOwnerIsHiddenAndStaffDriverAdminCannotCancel() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        for (String role : List.of("STAFF", "DRIVER", "ADMIN"))
            mockMvc.perform(post("/api/v1/owner/bookings/{id}/cancel", paid.bookingId()).with(user("other").roles(role))
                    .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json")
                    .content(json(Map.of("expectedVersion", 1, "reason", REASON)))).andExpect(status().isForbidden());
        currentActorId.set(driverId); ownerCancel(paid.bookingId(), 1, UUID.randomUUID(), REASON, 404);
        assertCount("refunds", paid.bookingId(), 0);
    }

    @Test void refusesClientAmountAndMissingKeyAndShortReason() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT); currentActorId.set(ownerId);
        mockMvc.perform(post("/api/v1/owner/bookings/{id}/cancel", paid.bookingId()).with(user("owner").roles("OWNER"))
                .header("Idempotency-Key", UUID.randomUUID()).contentType("application/json")
                .content(json(Map.of("expectedVersion", 1, "reason", REASON, "amount", paid.amount())))).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/owner/bookings/{id}/cancel", paid.bookingId()).with(user("owner").roles("OWNER"))
                .contentType("application/json").content(json(Map.of("expectedVersion", 1, "reason", REASON)))).andExpect(status().isBadRequest());
        ownerCancel(paid.bookingId(), 1, UUID.randomUUID(), "  a  ", 400); assertCount("refunds", paid.bookingId(), 0);
    }

    @Test void changedKeyPayloadAndStalePaymentVersionConflict() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT); currentActorId.set(ownerId);
        assertThat(ownerCancel(paid.bookingId(), 0, UUID.randomUUID(), REASON, 409).at("/error/code").asText()).isEqualTo("BKG_VERSION_CONFLICT");
        UUID key = UUID.randomUUID(); ownerCancel(paid.bookingId(), 1, key, REASON, 200);
        assertThat(ownerCancel(paid.bookingId(), 1, key, REASON + " đổi", 409).at("/error/code").asText()).isEqualTo("CMD_KEY_REUSED");
    }

    @ParameterizedTest @ValueSource(strings = {"CHECKED_IN", "CHARGING", "COMPLETED"})
    void doesNotCancelSessionsOrTerminalBooking(String state) throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        jdbc.update("UPDATE bookings SET status=?, checked_in_at=? WHERE id=?", state, Timestamp.from(NOW), paid.bookingId());
        currentActorId.set(ownerId); ownerCancel(paid.bookingId(), 1, UUID.randomUUID(), REASON, 409);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?", String.class, paid.bookingId())).isEqualTo(state);
        assertCount("refunds", paid.bookingId(), 0);
    }

    @Test void deadlineEqualityAndExpiredHoldRejectWithoutMutation() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        jdbc.update("UPDATE bookings SET check_in_deadline=? WHERE id=?", Timestamp.from(NOW), paid.bookingId());
        currentActorId.set(ownerId); ownerCancel(paid.bookingId(), 1, UUID.randomUUID(), REASON, 409);
        jdbc.update("UPDATE bookings SET status='CANCELLED', cancellation_reason='NO_SHOW' WHERE id=?", paid.bookingId());
        PendingBooking pending = createPendingBookingWithCheckout(START_AT.plusSeconds(7200));
        jdbc.update("UPDATE bookings SET expires_at=? WHERE id=?", Timestamp.from(NOW), pending.bookingId());
        currentActorId.set(ownerId); ownerCancel(pending.bookingId(), 0, UUID.randomUUID(), REASON, 409);
        assertCount("booking_service_failure_decisions", pending.bookingId(), 0);
    }

    @Test void refundFailureRollsBackBookingCommandAndDecision() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT); currentActorId.set(ownerId);
        RefundObligationService target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(obligations);
        doThrow(new AppException(RefundErrorCode.EXECUTION_CONFLICT)).when(target).createObligation(any());
        ownerCancel(paid.bookingId(), 1, UUID.randomUUID(), REASON, 409);
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?", String.class, paid.bookingId())).isEqualTo("CONFIRMED");
        assertCount("booking_service_failure_decisions", paid.bookingId(), 0); assertCount("refunds", paid.bookingId(), 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_commands WHERE booking_id=? AND operation='OWNER_CANCEL_BOOKING'", Long.class, paid.bookingId())).isZero();
    }

    @Test void admissionCapturesInputStateBeforeNoShowReconciliation() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        Instant deadline = jdbc.queryForObject("SELECT check_in_deadline FROM bookings WHERE id=?",
                Timestamp.class, paid.bookingId()).toInstant();
        when(applicationClock.instant()).thenReturn(deadline);
        currentActorId.set(ownerId);

        mockMvc.perform(post("/api/v1/owner/bookings/{id}/station-failure-refund", paid.bookingId())
                        .with(user("owner").roles("OWNER"))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json")
                        .content(json(Map.of("expectedVersion", 1, "expectedDecisionVersion", 0,
                                "affectedAt", START_AT.toString(), "reason", REASON))))
                .andExpect(status().isOk());

        JsonNode snapshot = objectMapper.readTree(jdbc.queryForObject(
                "SELECT evidence_snapshot::text FROM booking_service_failure_decisions WHERE booking_id=?",
                String.class, paid.bookingId()));
        assertThat(snapshot.path("persistedStatus").asText()).isEqualTo("CONFIRMED");
        assertThat(snapshot.path("bookingVersion").asLong()).isEqualTo(1L);
        assertThat(snapshot.has("cancellationReason")).isFalse();
        assertThat(jdbc.queryForObject("SELECT cancellation_reason FROM bookings WHERE id=?",
                String.class, paid.bookingId())).isEqualTo("NO_SHOW");
        assertThat(jdbc.queryForObject("SELECT version FROM bookings WHERE id=?",
                Long.class, paid.bookingId())).isEqualTo(2L);
        assertCount("refunds", paid.bookingId(), 1);
    }

    @Test void responsibilityIsAppendOnlyAtDatabaseLevel() throws Exception {
        PendingBooking pending = createPendingBookingWithCheckout(START_AT); currentActorId.set(ownerId);
        ownerCancel(pending.bookingId(), 0, UUID.randomUUID(), REASON, 200);
        assertThatThrownBy(() -> jdbc.update("UPDATE booking_service_failure_decisions SET reason='changed reason' WHERE booking_id=?", pending.bookingId())).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM booking_service_failure_decisions WHERE booking_id=?", pending.bookingId())).hasMessageContaining("append-only");
    }

    private JsonNode ownerCancel(UUID bookingId, long version, UUID key, String reason, int expectedStatus) throws Exception {
        var result = mockMvc.perform(post("/api/v1/owner/bookings/{id}/cancel", bookingId).with(user("owner").roles("OWNER"))
                .header("Idempotency-Key", key).contentType("application/json").content(json(Map.of("expectedVersion", version, "reason", reason))))
                .andExpect(status().is(expectedStatus));
        if (expectedStatus == 200) result.andExpect(header().string("Cache-Control", "no-store"));
        return body(result.andReturn());
    }
    private int concurrentCancel(PaidBooking paid, UUID key, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
        return mockMvc.perform(post("/api/v1/owner/bookings/{id}/cancel", paid.bookingId()).with(user("owner").roles("OWNER"))
                .header("Idempotency-Key", key).contentType("application/json").content(json(Map.of("expectedVersion", 1, "reason", REASON))))
                .andReturn().getResponse().getStatus();
    }
    private void assertCount(String table, UUID bookingId, long count) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE booking_id=?", Long.class, bookingId)).isEqualTo(count);
    }
    private void assertOwnerHistory(UUID bookingId) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_status_history WHERE booking_id=? AND actor_type='OWNER' AND reason='OWNER_CANCELLED'", Long.class, bookingId)).isEqualTo(1L);
    }

    private PaidBooking createAndPayBooking(Instant startAt) throws Exception {
        PendingBooking pending = createPendingBookingWithCheckout(startAt);
        JsonNode simulated = body(mockMvc.perform(post(
                                "/api/v1/bookings/{bookingId}/simulate-payment", pending.bookingId())
                        .with(user("driver").roles("DRIVER"))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json")
                        .content(json(Map.of(
                                "transactionRef", "TX-" + UUID.randomUUID(),
                                "outcome", "SUCCESS",
                                "amount", pending.amount(),
                                "currency", "VND",
                                "providerPaidAt", NOW.minusSeconds(1).toString()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.booking.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.booking.payment.status").value("PAID"))
                .andReturn());

        assertThat(simulated.at("/data/receipt/classification").asText()).isEqualTo("APPLIED");
        return new PaidBooking(
                pending.bookingId(), pending.paymentId(), pending.amount(), 1L
        );
    }

    private PendingBooking createPendingBookingWithCheckout(Instant startAt) throws Exception {
        currentActorId.set(driverId);
        JsonNode preview = body(mockMvc.perform(post("/api/v1/bookings/price-preview")
                        .with(user("driver").roles("DRIVER"))
                        .contentType("application/json")
                        .content(json(Map.of(
                                "connectorId", connectorId,
                                "startAt", startAt,
                                "durationMin", 60
                        ))))
                .andExpect(status().isOk())
                .andReturn());
        long amount = preview.at("/data/totalAmount").asLong();
        String pricingVersion = preview.at("/data/pricingVersion").asText();
        String policyVersion = preview.at("/data/policy/policyVersion").asText();

        JsonNode created = body(mockMvc.perform(post("/api/v1/bookings")
                        .with(user("driver").roles("DRIVER"))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json")
                        .content(json(Map.of(
                                "connectorId", connectorId,
                                "startAt", startAt,
                                "durationMin", 60,
                                "acceptedTotalAmount", amount,
                                "acceptedPricingVersion", pricingVersion,
                                "acceptedPolicyVersion", policyVersion,
                                "paymentMethod", "SIMULATOR"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn());
        UUID bookingId = UUID.fromString(created.at("/data/bookingId").asText());
        UUID paymentId = UUID.fromString(created.at("/data/payment/paymentId").asText());

        mockMvc.perform(post(
                                "/api/v1/bookings/{bookingId}/checkout", bookingId)
                        .with(user("driver").roles("DRIVER"))
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andReturn();
        var payment = paymentRepository.findById(paymentId).orElseThrow();

        return new PendingBooking(
                bookingId,
                paymentId,
                amount,
                payment.getPaymentCode(),
                payment.getVaNumber()
        );
    }

    private JsonNode body(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private Fixture insertBookableStationFixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        UUID ownerId = UUID.randomUUID();
        UUID fixtureDriverId = UUID.randomUUID();
        UUID fixtureAdminId = UUID.randomUUID();
        UUID stationId = UUID.randomUUID();
        UUID chargePointId = UUID.randomUUID();
        UUID fixtureConnectorId = UUID.randomUUID();
        UUID scheduleId = UUID.randomUUID();

        insertUser(ownerId, "owner-" + suffix, "Owner " + suffix);
        insertUser(fixtureDriverId, "driver-" + suffix, "Driver " + suffix);
        insertUser(fixtureAdminId, "admin-" + suffix, "Admin " + suffix);

        jdbc.update("""
                INSERT INTO stations
                    (id, station_code, owner_id, name, address_line,
                     latitude, longitude, contact_phone, planned_charge_point_count,
                     status, operational_status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'BKG-038 Test Address',
                        10.7769, 106.7009, '0900000000', 1,
                        'ACTIVE', 'OPERATING', 0, ?, ?)
                """, stationId, "ST-BKG38-" + suffix, ownerId, "BKG-038 Station " + suffix,
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO licenses
                    (id, license_code, station_id, owner_id, plan, fee_amount,
                     start_at, expires_at, status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'MONTHLY', 0, ?, ?, 'ACTIVE', 0, ?, ?)
                """, UUID.randomUUID(), "LIC-BKG38-" + suffix, stationId, ownerId,
                Timestamp.from(NOW.minusSeconds(3600)), Timestamp.from(NOW.plusSeconds(172800)),
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO station_booking_settings
                    (id, station_id, min_duration_minutes, duration_step_minutes,
                     max_duration_minutes, base_price_vnd, version, created_at, updated_at)
                VALUES (?, ?, 30, 30, 180, 3400, 0, ?, ?)
                """, UUID.randomUUID(), stationId, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO station_operating_schedules
                    (id, station_id, open_24_hours, effective_from, created_at, updated_at)
                VALUES (?, ?, true, ?, ?, ?)
                """, scheduleId, stationId, Timestamp.from(NOW.minusSeconds(3600)),
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO charge_points
                    (id, station_id, charge_point_code, max_power_kw,
                     provisioning_status, operational_status, version, created_at, updated_at)
                VALUES (?, ?, ?, 60, 'ACTIVE', 'AVAILABLE', 0, ?, ?)
                """, chargePointId, stationId, "CP-BKG38-" + suffix,
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO connectors
                    (id, charge_point_id, connector_code, connector_type, power_kw,
                     charger_type, runtime_status, version, created_at, updated_at)
                VALUES (?, ?, ?, 'CCS2', 60, 'DC', 'AVAILABLE', 0, ?, ?)
                """, fixtureConnectorId, chargePointId, "CON-BKG38-" + suffix,
                Timestamp.from(NOW), Timestamp.from(NOW));

        return new Fixture(fixtureDriverId, fixtureAdminId, fixtureConnectorId);
    }

    private void insertUser(UUID id, String keycloakId, String displayName) {
        jdbc.update("""
                INSERT INTO user_profile
                    (id, keycloak_id, email, display_name, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, id, keycloakId, keycloakId + "@example.test", displayName,
                Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private record Fixture(UUID driverId, UUID adminId, UUID connectorId) {
    }

    private record PendingBooking(
            UUID bookingId,
            UUID paymentId,
            long amount,
            String paymentCode,
            String vaNumber
    ) {
    }

    private record PaidBooking(
            UUID bookingId,
            UUID paymentId,
            long amount,
            long confirmedVersion
    ) {
    }
}
