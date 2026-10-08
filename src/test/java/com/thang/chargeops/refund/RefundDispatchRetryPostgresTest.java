package com.thang.chargeops.refund;

import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.common.enums.PaymentEnvironment;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.RefundErrorCode;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.service.impl.OwnerFinanceServiceImpl;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.refund.dto.request.RefundExecutionOutcome;
import com.thang.chargeops.refund.entity.RefundAttempt;
import com.thang.chargeops.refund.executor.*;
import com.thang.chargeops.refund.model.*;
import com.thang.chargeops.refund.repository.*;
import com.thang.chargeops.refund.service.*;
import com.thang.chargeops.refund.service.impl.*;
import com.thang.chargeops.station.repository.ConnectorRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

/** Committed fixtures and independent connections: no surrounding test transaction hides races. */
@Testcontainers
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "spring.docker.compose.enabled=false", "spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace = NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ActiveProfiles("test")
@Import({RefundObligationServiceImpl.class, AutomaticRefundExecutionServiceImpl.class,
        OwnerRefundServiceImpl.class, OwnerFinanceServiceImpl.class, RefundDetailAssembler.class,
        RefundExecutorRegistry.class, SimulatorRefundExecutor.class, RefundExecutionResultHandler.class,
        RefundDispatchRetryPostgresTest.TimeConfig.class})
class RefundDispatchRetryPostgresTest {
    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");
    private static final long AMOUNT = 120000;
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine").asCompatibleSubstituteFor("postgres"));
    @TestConfiguration static class TimeConfig {
        @Bean Clock applicationClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager transactions;
    @Autowired UserProfileRepository users;
    @Autowired ConnectorRepository connectors;
    @Autowired BookingRepository bookings;
    @MockitoSpyBean PaymentRepository payments;
    @Autowired RefundRepository refunds;
    @Autowired RefundAttemptRepository attempts;
    @Autowired RefundAutoDispatchRepository dispatches;
    @Autowired RefundObligationService obligations;
    @Autowired AutomaticRefundExecutionService automatic;
    @Autowired OwnerRefundService ownerRefunds;
    @Autowired OwnerFinanceServiceImpl finance;
    @MockitoBean CurrentProfileProvider profiles;
    @MockitoSpyBean SimulatorRefundExecutor simulator;
    @MockitoSpyBean RefundExecutionResultHandler results;
    private UUID ownerId, bookingId, paymentId, receiptId, connectorId;

    @BeforeEach void seedCommittedPackage() {
        ownerId = UUID.randomUUID(); bookingId = UUID.randomUUID(); paymentId = UUID.randomUUID();
        receiptId = UUID.randomUUID(); connectorId = UUID.randomUUID();
        UUID stationId = UUID.randomUUID(), pointId = UUID.randomUUID();
        String code = "BKG075" + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
        jdbc.update("INSERT INTO user_profile(id,keycloak_id,email) VALUES (?,?,?)", ownerId, code, code + "@example.test");
        jdbc.update("INSERT INTO stations(id,owner_id,name,address_line,contact_phone,status) VALUES (?,?,?,'Test','000','ACTIVE')", stationId, ownerId, code);
        jdbc.update("INSERT INTO charge_points(id,station_id,charge_point_code,provisioning_status,operational_status) VALUES (?,?,?,'ACTIVE','AVAILABLE')", pointId, stationId, code);
        jdbc.update("INSERT INTO connectors(id,charge_point_id,connector_code,connector_type,power_kw,charger_type,runtime_status) VALUES (?,?,?,'CCS2',60,'DC','AVAILABLE')", connectorId, pointId, code);
        jdbc.update("""
                INSERT INTO bookings(id,driver_id,connector_id,booking_code,start_at,end_at,status,total_amount,
                  station_name_snapshot,station_address_snapshot,charge_point_code_snapshot,connector_code_snapshot,expires_at)
                VALUES (?,?,?,?,'2026-10-07 05:00Z','2026-10-07 06:00Z','CONFIRMED',120000,?,'Test',?,?,'2026-10-07 04:00Z')
                """, bookingId, ownerId, connectorId, code, code, code, code);
        jdbc.update("""
                INSERT INTO payments(id,booking_id,amount,status,method,refund_amount,paid_at,provider,receiving_account_ref,
                  currency,needs_reconciliation,environment,version,payment_code)
                VALUES (?,?,120000,'PAID','SIMULATOR',0,'2026-10-07 02:59Z','SIMULATOR','SIMULATOR','VND',false,'SIMULATOR',0,?)
                """, paymentId, bookingId, code);
        jdbc.update("""
                INSERT INTO payment_transactions(id,payment_id,provider,receiving_account_ref,transaction_ref,amount,
                  currency,received_at,application_classification,payment_code,version)
                VALUES (?,?,'SIMULATOR','SIMULATOR',?,120000,'VND','2026-10-07 02:59Z','APPLIED',?,0)
                """, receiptId, paymentId, code, code);
        when(profiles.requireProfileId()).thenAnswer(i -> ownerId);
        when(profiles.requireProfile()).thenAnswer(i -> users.findById(ownerId).orElseThrow());
    }

    @Test void obligationAndDispatchAreInvisibleBeforeCommitAndRollbackTogether() throws Exception {
        try (var pool = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
                UUID id = createLockedObligation();
                em.flush();
                try {
                    assertThat(pool.submit(() -> jdbc.queryForObject(
                            "SELECT count(*) FROM refund_auto_dispatches WHERE refund_id=?", Long.class, id))
                            .get(10, TimeUnit.SECONDS)).isZero();
                } catch (Exception failure) { throw new AssertionError(failure); }
                throw new InterruptedExecution();
            })).isInstanceOf(InterruptedExecution.class);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refunds WHERE payment_id=?", Long.class, paymentId)).isZero();
        verifyNoInteractions(simulator);
        UUID id = newRefund();
        assertPending(id, 0);
        assertThat(dispatches.findPendingRefundIds(1000)).contains(id);
    }

    enum CrashPoint { BEFORE_EXECUTOR, AFTER_EXECUTOR, AFTER_ATTEMPT_FLUSH, AFTER_RESULT_FLUSH, BEFORE_COMMIT }
    @ParameterizedTest @EnumSource(CrashPoint.class)
    void interruptedFirstDispatchRollsBackAndRedeliveryUsesSameKey(CrashPoint point) {
        UUID id = newRefund();
        UUID key = jdbc.queryForObject("SELECT request_key FROM refund_auto_dispatches WHERE refund_id=?", UUID.class, id);
        if (point == CrashPoint.BEFORE_EXECUTOR || point == CrashPoint.AFTER_EXECUTOR) {
            doAnswer(i -> {
                if (point == CrashPoint.AFTER_EXECUTOR) i.callRealMethod();
                throw new InterruptedExecution();
            }).when(simulator).execute(any());
        } else if (point == CrashPoint.AFTER_ATTEMPT_FLUSH) {
            doThrow(new InterruptedExecution()).when(payments).flush();
        } else if (point == CrashPoint.AFTER_RESULT_FLUSH) {
            doAnswer(i -> { i.callRealMethod(); throw new InterruptedExecution(); })
                    .when(results).apply(any(), any(), any(), any(), any());
        }
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
            automatic.processFirstAttempt(id);
            em.flush();
            if (point == CrashPoint.BEFORE_COMMIT) throw new InterruptedExecution();
        })).isInstanceOf(InterruptedExecution.class);
        assertPending(id, 0);
        assertThat(jdbc.queryForObject("SELECT status FROM refund_auto_dispatches WHERE refund_id=?", String.class, id)).isEqualTo("PENDING");
        doCallRealMethod().when(simulator).execute(any());
        doCallRealMethod().when(results).apply(any(), any(), any(), any(), any());
        reset(payments);
        assertThat(automatic.processFirstAttempt(id)).isTrue();
        assertThat(automatic.processFirstAttempt(id)).isFalse();
        assertSucceeded(id, 1);
        assertThat(jdbc.queryForObject("SELECT request_key FROM refund_attempts WHERE refund_id=?", UUID.class, id)).isEqualTo(key);
    }

    @Test void lostResponseAfterCommittedSuccessNeverExecutesAgain() {
        UUID id = newRefund();
        assertThat(automatic.processFirstAttempt(id)).isTrue();
        for (int replay = 0; replay < 3; replay++) assertThat(automatic.processFirstAttempt(id)).isFalse();
        verify(simulator, times(1)).execute(any());
        assertSucceeded(id, 1);
    }

    @Test void concurrentDispatchWorkersHaveOneSuccess() throws Exception {
        UUID id = newRefund();
        CountDownLatch executing = new CountDownLatch(1), release = new CountDownLatch(1);
        blockExecutor(executing, release);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> automatic.processFirstAttempt(id));
            try {
                assertThat(executing.await(10, TimeUnit.SECONDS)).isTrue();
                var second = pool.submit(() -> automatic.processFirstAttempt(id));
                awaitDatabaseLock("connectors");
                release.countDown();
                assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder(true, false);
            } finally { release.countDown(); }
        }
        verify(simulator, times(1)).execute(any());
        assertSucceeded(id, 1);
    }

    @Test void failedFirstAttemptIsNotAutomaticallyRetriedAndOwnerCanRetry() {
        UUID id = failedFirst();
        assertThat(automatic.processFirstAttempt(id)).isFalse();
        assertPending(id, 1);
        assertThat(ownerRefunds.get(id).requiresOwnerAction()).isTrue();
        String original = auditAttempts(id);
        long version = version(id);
        UUID key = UUID.randomUUID();
        var result = ownerRefunds.retry(id, key, version);
        assertThat(result.status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(ownerRefunds.retry(id, key, version)).isEqualTo(result);
        assertSucceeded(id, 2);
        assertThat(auditFirstAttempt(id)).isEqualTo(original);
        verify(simulator, times(2)).execute(any());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void concurrentOwnerRetriesReplaySameKeyOrRejectOtherKey(boolean sameKey) throws Exception {
        UUID id = failedFirst();
        long version = version(id);
        UUID key = UUID.randomUUID();
        CountDownLatch executing = new CountDownLatch(1), release = new CountDownLatch(1);
        blockExecutor(executing, release);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> ownerRefunds.retry(id, key, version));
            try {
                assertThat(executing.await(10, TimeUnit.SECONDS)).isTrue();
                var second = pool.submit(() -> ownerRefunds.retry(id, sameKey ? key : UUID.randomUUID(), version));
                awaitDatabaseLock("user_profile");
                release.countDown();
                var winner = first.get(20, TimeUnit.SECONDS);
                if (sameKey) assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(winner);
                else assertThatThrownBy(() -> second.get(20, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(AppException.class);
            } finally { release.countDown(); }
        }
        verify(simulator, times(2)).execute(any()); // one failed first attempt plus one retry
        assertSucceeded(id, 2);
    }

    @Test void replayFailedOwnerAttemptDoesNotCreateAnotherAttempt() {
        UUID id = failedFirst();
        long version = version(id); UUID key = UUID.randomUUID();
        failSimulator("SIMULATED_FAILURE", "Second failure");
        var failed = ownerRefunds.retry(id, key, version);
        assertThat(failed.status()).isEqualTo(RefundStatus.PENDING);
        assertThat(ownerRefunds.retry(id, key, version)).isEqualTo(failed);
        assertPending(id, 2);
        verify(simulator, times(2)).execute(any());
        doCallRealMethod().when(simulator).execute(any());
        ownerRefunds.retry(id, UUID.randomUUID(), version(id));
        assertSucceeded(id, 3);
    }

    @Test void retryRejectsChangedPayloadStaleVersionAndNewKeyAfterSuccess() {
        UUID id = failedFirst(); long version = version(id); UUID key = UUID.randomUUID();
        expectError(() -> ownerRefunds.retry(id, UUID.randomUUID(), version + 1), RefundErrorCode.VERSION_CONFLICT);
        ownerRefunds.retry(id, key, version);
        expectError(() -> ownerRefunds.retry(id, key, version + 1), RefundErrorCode.REQUEST_CONFLICT);
        expectError(() -> ownerRefunds.retry(id, UUID.randomUUID(), version), RefundErrorCode.VERSION_CONFLICT);
        expectError(() -> ownerRefunds.retry(id, UUID.randomUUID(), version(id)), RefundErrorCode.EXECUTION_CONFLICT);
        assertSucceeded(id, 2);
        verify(simulator, times(2)).execute(any());
    }

    @Test void ownerCannotRetryBeforeTheFirstAttempt() {
        UUID id = newRefund();
        expectError(() -> ownerRefunds.retry(id, UUID.randomUUID(), version(id)), RefundErrorCode.EXECUTION_CONFLICT);
        assertPending(id, 0); verifyNoInteractions(simulator);
    }

    @Test void historicalStartedAttemptCannotBeRetriedAsFailed() {
        UUID id = newRefund();
        tx().executeWithoutResult(status -> {
            var refund = refunds.findByIdWithLock(id).orElseThrow();
            var actor = users.findById(ownerId).orElseThrow();
            attempts.saveAndFlush(RefundAttempt.start(new PendingRefundAttemptSpec(refund, 1,
                    RefundExecutionMode.SIMULATOR, UUID.randomUUID(), "a".repeat(64), "legacy-started:" + id,
                    RefundExecutionTrigger.ADMIN, actor, NOW.minusSeconds(1))));
        });
        assertThat(automatic.processFirstAttempt(id)).isFalse();
        expectError(() -> ownerRefunds.retry(id, UUID.randomUUID(), version(id)), RefundErrorCode.EXECUTION_CONFLICT);
        assertPending(id, 1); verifyNoInteractions(simulator);
    }

    @Test void interruptedOwnerRetryCanBeResentWithSameKeyWithoutLosingHistory() {
        UUID id = failedFirst(); String audit = auditAttempts(id);
        long version = version(id); UUID key = UUID.randomUUID();
        doAnswer(i -> { i.callRealMethod(); throw new InterruptedExecution(); })
                .when(results).apply(any(), any(), any(), any(), any());
        assertThatThrownBy(() -> ownerRefunds.retry(id, key, version)).isInstanceOf(InterruptedExecution.class);
        assertPending(id, 1); assertThat(auditAttempts(id)).isEqualTo(audit);
        doCallRealMethod().when(results).apply(any(), any(), any(), any(), any());
        ownerRefunds.retry(id, key, version);
        assertSucceeded(id, 2); assertThat(auditFirstAttempt(id)).isEqualTo(audit);
    }

    @Test void ownerGetsSafeFailureWhileHistoricalAdminAuditRemainsUnchanged() {
        UUID id = newRefund();
        tx().executeWithoutResult(status -> {
            var refund = refunds.findByIdWithLock(id).orElseThrow();
            var actor = users.findById(ownerId).orElseThrow();
            var attempt = RefundAttempt.start(new PendingRefundAttemptSpec(refund, 1,
                    RefundExecutionMode.SIMULATOR, UUID.randomUUID(), "b".repeat(64), "legacy-admin:" + id,
                    RefundExecutionTrigger.ADMIN, actor, NOW.minusSeconds(1)));
            attempt.completeFailed("INTERNAL_SECRET_MARKER", "jdbc:internal.example token=SECRET_MARKER",
                    NOW, NOW);
            attempts.saveAndFlush(attempt); refund.requireOwnerAction(); refunds.flush();
        });
        String audit = auditAttempts(id);
        var failed = ownerRefunds.get(id).attempts().getFirst();
        assertThat(failed.failureCode()).isEqualTo("REFUND_EXECUTION_FAILED");
        assertThat(failed.note()).doesNotContain("SECRET_MARKER", "jdbc:", "internal.example");
        assertThat(failed.executionTrigger()).isEqualTo(RefundExecutionTrigger.ADMIN);
        automatic.processFirstAttempt(id); // existing attempt consumes dispatch without executing it
        ownerRefunds.retry(id, UUID.randomUUID(), version(id));
        assertThat(auditFirstAttempt(id)).isEqualTo(audit);
        assertSucceeded(id, 2);
    }

    @ParameterizedTest @EnumSource(value = PaymentEnvironment.class, names = {"LIVE", "LEGACY", "TEST"})
    void nonSimulatorHistoryNeverRunsSimulatorOrOwnerRetry(PaymentEnvironment environment) {
        UUID id = newRefund();
        jdbc.update("UPDATE payments SET environment=? WHERE id=?", environment.name(), paymentId);
        assertThat(automatic.processFirstAttempt(id)).isFalse();
        expectError(() -> ownerRefunds.retry(id, UUID.randomUUID(), version(id)), RefundErrorCode.EXECUTION_CONFLICT);
        assertThat(attempts.countByRefundId(id)).isZero();
        assertThat(jdbc.queryForObject("SELECT refund_amount FROM payments WHERE id=?", Long.class, paymentId)).isZero();
        verifyNoInteractions(simulator);
    }

    private UUID newRefund() { return tx().execute(status -> createLockedObligation()); }
    private UUID createLockedObligation() {
        var actor = users.findByIdWithLock(ownerId).orElseThrow();
        connectors.findByIdWithLock(connectorId).orElseThrow();
        var booking = bookings.findByIdWithLock(bookingId).orElseThrow();
        var payment = payments.findByIdWithLock(paymentId).orElseThrow();
        return obligations.createObligation(new CreateRefundObligationCommand(booking, payment, receiptId,
                RefundBasisType.BOOKING_CANCELLATION, UUID.randomUUID(), RefundReason.VOLUNTARY_GRACE,
                actor, NOW.minusSeconds(2))).getId();
    }
    private UUID failedFirst() {
        UUID id = newRefund(); failSimulator("SIMULATED_FAILURE", "Temporary Simulator failure");
        assertThat(automatic.processFirstAttempt(id)).isTrue();
        doCallRealMethod().when(simulator).execute(any());
        assertPending(id, 1); return id;
    }
    private void failSimulator(String code, String note) {
        doAnswer(i -> {
            RefundExecutionCommand command = i.getArgument(0);
            return new RefundExecutionResult(RefundExecutionOutcome.FAILED, "SIMULATOR", null, code,
                    command.executionAt(), note);
        }).when(simulator).execute(any());
    }
    private void blockExecutor(CountDownLatch executing, CountDownLatch release) {
        doAnswer(i -> {
            executing.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("executor release timeout");
            return i.callRealMethod();
        }).when(simulator).execute(any());
    }
    private void awaitDatabaseLock(String table) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE ?",
                Long.class, "%" + table + "%") > 0);
    }
    private TransactionTemplate tx() { return new TransactionTemplate(transactions); }
    private long version(UUID id) { return jdbc.queryForObject("SELECT version FROM refunds WHERE id=?", Long.class, id); }
    private void assertPending(UUID id, long count) {
        assertThat(jdbc.queryForObject("SELECT status FROM refunds WHERE id=?", String.class, id)).isEqualTo("PENDING");
        assertThat(attempts.countByRefundId(id)).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT refund_amount FROM payments WHERE id=?", Long.class, paymentId)).isZero();
        assertThat(finance.summary().netVnd()).isEqualTo(AMOUNT);
        assertThat(finance.summary().pendingRefundVnd()).isEqualTo(AMOUNT);
    }
    private void assertSucceeded(UUID id, long count) {
        assertThat(jdbc.queryForObject("SELECT status FROM refunds WHERE id=?", String.class, id)).isEqualTo("SUCCEEDED");
        assertThat(attempts.countByRefundId(id)).isEqualTo(count);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refund_attempts WHERE refund_id=? AND status='SUCCEEDED'", Long.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT refund_amount FROM payments WHERE id=?", Long.class, paymentId)).isEqualTo(AMOUNT);
        assertThat(jdbc.queryForObject("SELECT status FROM refund_auto_dispatches WHERE refund_id=?", String.class, id)).isEqualTo("PROCESSED");
        assertThat(finance.summary().refundedVnd()).isEqualTo(AMOUNT);
        assertThat(finance.summary().netVnd()).isZero();
        assertThat(finance.summary().pendingRefundVnd()).isZero();
    }
    private String auditAttempts(UUID id) {
        return jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(a) ORDER BY sequence_no)::text FROM refund_attempts a WHERE refund_id=?", String.class, id);
    }
    private String auditFirstAttempt(UUID id) {
        return jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(a))::text FROM refund_attempts a WHERE refund_id=? AND sequence_no=1", String.class, id);
    }
    private void expectError(Runnable call, RefundErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(AppException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
    static class InterruptedExecution extends RuntimeException { }
}
