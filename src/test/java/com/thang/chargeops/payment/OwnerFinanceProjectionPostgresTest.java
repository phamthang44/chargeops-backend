package com.thang.chargeops.payment;

import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.common.enums.PaymentApplicationClassification;
import com.thang.chargeops.common.enums.PaymentEnvironment;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.exception.errorcode.PaymentErrorCode;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.entity.PaymentTransaction;
import com.thang.chargeops.payment.dto.response.OwnerFinanceSummaryResponse;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.payment.service.impl.OwnerFinanceServiceImpl;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.refund.entity.Refund;
import com.thang.chargeops.refund.entity.RefundAttempt;
import com.thang.chargeops.refund.executor.RefundExecutorRegistry;
import com.thang.chargeops.refund.executor.SimulatorRefundExecutor;
import com.thang.chargeops.refund.model.*;
import com.thang.chargeops.refund.repository.RefundAttemptRepository;
import com.thang.chargeops.refund.repository.RefundRepository;
import com.thang.chargeops.refund.service.RefundDetailAssembler;
import com.thang.chargeops.refund.service.RefundExecutionResultHandler;
import com.thang.chargeops.refund.service.impl.OwnerRefundServiceImpl;
import com.thang.chargeops.station.repository.StationRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.docker.compose.enabled=false"
})
@AutoConfigureTestDatabase(replace = NONE)
@ActiveProfiles("test")
@Import({
        OwnerFinanceServiceImpl.class,
        OwnerRefundServiceImpl.class,
        RefundDetailAssembler.class,
        RefundExecutorRegistry.class,
        SimulatorRefundExecutor.class,
        RefundExecutionResultHandler.class,
        OwnerFinanceProjectionPostgresTest.TimeConfig.class
})
class OwnerFinanceProjectionPostgresTest {

    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine").asCompatibleSubstituteFor("postgres"));

    @TestConfiguration
    static class TimeConfig {
        @Bean
        Clock applicationClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @MockitoBean CurrentProfileProvider profiles;
    @MockitoSpyBean SimulatorRefundExecutor simulator;
    @Autowired OwnerFinanceServiceImpl finance;
    @Autowired BookingRepository bookings;
    @Autowired PaymentRepository payments;
    @Autowired PaymentTransactionRepository receipts;
    @Autowired RefundRepository refunds;
    @Autowired RefundAttemptRepository attempts;
    @Autowired UserProfileRepository users;
    @Autowired StationRepository stations;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager txManager;

    private UUID ownerA, bookingA, paymentA;

    @BeforeEach
    void seedBaselineData() {
        jdbc.execute("""
            INSERT INTO user_profile(keycloak_id, email) VALUES
                ('scope-074-a', 'scope-074-a@example.test'),
                ('scope-074-b', 'scope-074-b@example.test');
            INSERT INTO stations(owner_id, name, address_line, contact_phone, status)
                SELECT id, keycloak_id, 'Test Address', '0000000000', 'ACTIVE'
                FROM user_profile WHERE keycloak_id LIKE 'scope-074-%';
            INSERT INTO charge_points(station_id, charge_point_code, provisioning_status, operational_status)
                SELECT id, name, 'ACTIVE', 'AVAILABLE'
                FROM stations WHERE name LIKE 'scope-074-%';
            INSERT INTO connectors(charge_point_id, connector_code, connector_type, power_kw, charger_type, runtime_status)
                SELECT id, charge_point_code, 'CCS2', 60, 'DC', 'AVAILABLE'
                FROM charge_points WHERE charge_point_code LIKE 'scope-074-%';
            INSERT INTO bookings(driver_id, connector_id, booking_code, start_at, end_at, status, total_amount,
                station_name_snapshot, station_address_snapshot, charge_point_code_snapshot, connector_code_snapshot, expires_at,
                financial_owner_id)
                SELECT s.owner_id, c.id, s.name, '2026-10-06 12:00Z', '2026-10-06 13:00Z', 'CONFIRMED', 120000,
                    s.name, 'Test Address', cp.charge_point_code, c.connector_code, '2026-10-06 11:10Z',
                    (SELECT id FROM user_profile WHERE keycloak_id = 'scope-074-a')
                FROM connectors c
                JOIN charge_points cp ON cp.id = c.charge_point_id
                JOIN stations s ON s.id = cp.station_id
                WHERE s.name = 'scope-074-a';
            INSERT INTO payments(booking_id, amount, status, method, refund_amount, paid_at, provider,
                receiving_account_ref, currency, needs_reconciliation, environment, version, payment_code)
                SELECT id, 120000, 'PAID', 'SIMULATOR', 0, now(), 'SIMULATOR', 'SIMULATOR', 'VND', false, 'SIMULATOR', 0,
                    'SCOPE074A'
                FROM bookings WHERE booking_code = 'scope-074-a';
            INSERT INTO payment_transactions(payment_id, provider, receiving_account_ref, transaction_ref,
                amount, currency, received_at, application_classification, payment_code, version)
                SELECT id, 'SIMULATOR', 'SIMULATOR', 'TX-APPLIED-1', 120000, 'VND', now(), 'APPLIED', payment_code, 0
                FROM payments WHERE payment_code = 'SCOPE074A';
        """);

        ownerA = users.findByKeycloakId("scope-074-a").orElseThrow().getId();
        var payment = payments.findByPaymentCode("SCOPE074A").orElseThrow();
        paymentA = payment.getId();
        bookingA = payment.getBooking().getId();

        when(profiles.requireProfileId()).thenReturn(ownerA);
        when(profiles.requireProfile()).thenAnswer(inv -> users.findById(ownerA).orElseThrow());
    }

    @Test
    void antiMultiplicationTestWithMultipleReceiptsAndAttempts() {
        // Add an UNAPPLIED receipt (30,000 VND)
        jdbc.execute(String.format("""
            INSERT INTO payment_transactions(payment_id, provider, receiving_account_ref, transaction_ref,
                amount, currency, received_at, application_classification, application_reason, payment_code, version)
            VALUES ('%s', 'SIMULATOR', 'SIMULATOR', 'TX-UNAPPLIED-OVERPAY', 30000, 'VND', now(), 'UNAPPLIED', 'EXCESS', 'SCOPE074A', 0);
        """, paymentA));

        // Create a refund for 120,000 with 2 attempts: 1 FAILED, 1 SUCCEEDED
        var payment = payments.findById(paymentA).orElseThrow();
        var appliedReceipt = receipts.findByPaymentIdOrderByReceivedAtAscIdAsc(paymentA).stream()
                .filter(receipt -> receipt.getApplicationClassification() == PaymentApplicationClassification.APPLIED)
                .findFirst().orElseThrow();
        var actor = users.findById(ownerA).orElseThrow();

        var refund = refunds.saveAndFlush(Refund.createPending(new PendingRefundSpec(
                payment.getBooking(), payment, appliedReceipt, RefundReason.VOLUNTARY_GRACE,
                RefundBasisType.BOOKING_CANCELLATION, UUID.randomUUID(), NOW.minusSeconds(10), actor)));

        // Attempt 1: FAILED
        UUID key1 = UUID.randomUUID();
        var attempt1 = RefundAttempt.start(new PendingRefundAttemptSpec(refund, 1, RefundExecutionMode.SIMULATOR,
                key1, "a".repeat(64), "refund:" + key1, RefundExecutionTrigger.OWNER, actor, NOW.minusSeconds(8)));
        attempt1.completeFailed("NETWORK_TIMEOUT", "Timeout occurred", NOW.minusSeconds(7), NOW.minusSeconds(7));
        attempts.saveAndFlush(attempt1);

        // Attempt 2: SUCCEEDED
        UUID key2 = UUID.randomUUID();
        var attempt2 = RefundAttempt.start(new PendingRefundAttemptSpec(refund, 2, RefundExecutionMode.SIMULATOR,
                key2, "b".repeat(64), "refund:" + key2, RefundExecutionTrigger.OWNER, actor, NOW.minusSeconds(5)));
        attempt2.completeSucceeded("SIM_REF_1", "TRANSFER_1", NOW.minusSeconds(4));
        attempts.saveAndFlush(attempt2);

        refund.completeWith(attempt2, NOW.minusSeconds(4));
        refunds.saveAndFlush(refund);

        payment.recordFullRefund(BigDecimal.valueOf(120000));
        payments.saveAndFlush(payment);

        // Add 2 ticket findings to booking
        jdbc.execute(String.format("""
            INSERT INTO support_tickets(ticket_code, booking_id, reporter_id, category, status, priority, subject, description)
            VALUES ('TKT-20261007-0074', '%s', '%s', 'CHARGING_ISSUE', 'OPEN', 'MEDIUM', 'Ticket 1', 'Test ticket');
        """, bookingA, ownerA));
        UUID ticketId = jdbc.queryForObject("SELECT id FROM support_tickets WHERE booking_id = '" + bookingA + "' LIMIT 1", UUID.class);
        jdbc.execute(String.format("""
            INSERT INTO ticket_findings(ticket_id, booking_id, recorded_by, conclusion, reason, affected_at, recorded_at)
            VALUES ('%s', '%s', '%s', 'STATION_FAILURE', 'Finding 1', now(), now()),
                   ('%s', '%s', '%s', 'STATION_FAILURE', 'Finding 2', now(), now());
        """, ticketId, bookingA, ownerA, ticketId, bookingA, ownerA));
        jdbc.update("""
            INSERT INTO ticket_events(ticket_id, actor_id, actor_kind, event_type, resolution_cycle, created_at)
            SELECT ?, ?, 'OWNER', 'CLAIMED', 0, now() FROM generate_series(1, 3)
        """, ticketId, ownerA);

        em.clear();

        // Verify summary
        var summary = finance.summary();
        assertThat(summary.grossVnd()).isEqualTo(120000);
        assertThat(summary.refundedVnd()).isEqualTo(120000);
        assertThat(summary.netVnd()).isZero();
        assertThat(summary.pendingRefundVnd()).isZero();
        assertThat(summary.totalBookings()).isEqualTo(1);

        // Verify list
        var list = finance.list(1, 10).getContent();
        assertThat(list).hasSize(1);
        var row = list.getFirst();
        assertThat(row.collectedAmount()).isEqualTo(120000);
        assertThat(row.refundedAmount()).isEqualTo(120000);
        assertThat(row.netRecordedAmount()).isZero();
        assertThat(row.pendingRefundAmount()).isZero();

        // Verify get detail
        var detail = finance.get(bookingA);
        assertThat(detail.collectedAmount()).isEqualTo(120000);
        assertThat(detail.refundedAmount()).isEqualTo(120000);
        assertThat(detail.receipts()).hasSize(1); // Only APPLIED receipt is present
    }

    @Test
    void offsettingDriftBetweenTwoBookingsIsDetected() {
        // Seed a second booking for ownerA
        jdbc.execute(String.format("""
            INSERT INTO bookings(driver_id, connector_id, booking_code, start_at, end_at, status, total_amount,
                station_name_snapshot, station_address_snapshot, charge_point_code_snapshot, connector_code_snapshot, expires_at,
                financial_owner_id)
            SELECT s.owner_id, c.id, 'scope-074-a-2', '2026-10-06 14:00Z', '2026-10-06 15:00Z', 'CONFIRMED', 120000,
                s.name, 'Test Address', cp.charge_point_code, c.connector_code, '2026-10-06 13:10Z',
                (SELECT id FROM user_profile WHERE keycloak_id = 'scope-074-a')
            FROM connectors c JOIN charge_points cp ON cp.id=c.charge_point_id JOIN stations s ON s.id=cp.station_id
            WHERE s.name = 'scope-074-a';

            INSERT INTO payments(booking_id, amount, status, method, refund_amount, paid_at, provider,
                receiving_account_ref, currency, needs_reconciliation, environment, version, payment_code)
            SELECT id, 120000, 'PAID', 'SIMULATOR', 0, now(), 'SIMULATOR', 'SIMULATOR', 'VND', false, 'SIMULATOR', 0,
                'SCOPE074A2'
            FROM bookings WHERE booking_code = 'scope-074-a-2';

            INSERT INTO payment_transactions(payment_id, provider, receiving_account_ref, transaction_ref,
                amount, currency, received_at, application_classification, payment_code, version)
            SELECT id, 'SIMULATOR', 'SIMULATOR', 'TX-APPLIED-2', 120000, 'VND', now(), 'APPLIED', payment_code, 0
            FROM payments WHERE payment_code = 'SCOPE074A2';
        """));

        // Corrupt mirror fields via direct SQL:
        // Booking 1: amount = 140000 (+20000)
        // Booking 2: amount = 100000 (-20000)
        // Total mirror = 240000, Total source = 240000. Offsetting error!
        jdbc.execute(String.format("UPDATE payments SET amount = 140000 WHERE id = '%s'", paymentA));
        UUID payment2 = jdbc.queryForObject("SELECT id FROM payments WHERE payment_code = 'SCOPE074A2'", UUID.class);
        jdbc.execute(String.format("UPDATE payments SET amount = 100000 WHERE id = '%s'", payment2));

        em.clear();

        // Must throw PROJECTION_BOUNDS_VIOLATED
        assertThatThrownBy(() -> finance.summary())
                .isInstanceOfSatisfying(AppException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(PaymentErrorCode.PROJECTION_BOUNDS_VIOLATED);
                });
    }

    @Test
    void driftOnListAndDetailThrowsException() {
        // Full-package amount obeys the DB constraint, but has no successful refund evidence.
        jdbc.execute(String.format("UPDATE payments SET refund_amount = amount WHERE id = '%s'", paymentA));
        em.clear();

        assertThatThrownBy(() -> finance.list(1, 10))
                .isInstanceOfSatisfying(AppException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(PaymentErrorCode.PROJECTION_BOUNDS_VIOLATED);
                });

        assertThatThrownBy(() -> finance.get(bookingA))
                .isInstanceOfSatisfying(AppException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(PaymentErrorCode.PROJECTION_BOUNDS_VIOLATED);
                });
    }

    @Test
    void nullPaidAtOnSettledPaymentThrowsDriftException() {
        // Corrupt paid_at to null on PAID payment
        jdbc.execute(String.format("UPDATE payments SET paid_at = NULL WHERE id = '%s'", paymentA));
        em.clear();

        assertThatThrownBy(() -> finance.summary())
                .isInstanceOfSatisfying(AppException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(PaymentErrorCode.PROJECTION_BOUNDS_VIOLATED);
                });

        assertThatThrownBy(() -> finance.list(1, 10))
                .isInstanceOfSatisfying(AppException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(PaymentErrorCode.PROJECTION_BOUNDS_VIOLATED);
                });

        assertThatThrownBy(() -> finance.get(bookingA))
                .isInstanceOfSatisfying(AppException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(PaymentErrorCode.PROJECTION_BOUNDS_VIOLATED);
                });
    }

    @Test
    void orphanAppliedReceiptOutsideSettledLedgerIsDetected() {
        // Set payment status to PENDING while it has an APPLIED receipt
        jdbc.execute(String.format("UPDATE payments SET status = 'PENDING' WHERE id = '%s'", paymentA));
        em.clear();

        assertThatThrownBy(() -> finance.summary())
                .isInstanceOfSatisfying(AppException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(PaymentErrorCode.PROJECTION_BOUNDS_VIOLATED);
                });
    }

    @Test
    void environmentIsolationExcludesNonSimulator() {
        for (var environment : new PaymentEnvironment[] {
                PaymentEnvironment.TEST, PaymentEnvironment.LIVE, PaymentEnvironment.LEGACY }) {
            jdbc.update("UPDATE payments SET environment = ? WHERE id = ?", environment.name(), paymentA);
            em.clear();
            var summary = finance.summary();
            assertThat(summary.grossVnd()).as(environment.name()).isZero();
            assertThat(summary.refundedVnd()).isZero();
            assertThat(summary.pendingRefundVnd()).isZero();
            assertThat(summary.totalBookings()).isZero();
            assertThat(finance.list(1, 10).getContent()).isEmpty();
            assertThatThrownBy(() -> finance.get(bookingA))
                    .isInstanceOfSatisfying(AppException.class, ex ->
                            assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.RESOURCE_NOT_FOUND));
        }
    }

    @Test
    void pendingRefundDoesNotReduceNetAndIsTrackedSeparately() {
        var payment = payments.findById(paymentA).orElseThrow();
        var appliedReceipt = receipts.findByPaymentIdOrderByReceivedAtAscIdAsc(paymentA).getFirst();
        var actor = users.findById(ownerA).orElseThrow();

        // Create PENDING refund with 1 FAILED attempt
        var refund = refunds.saveAndFlush(Refund.createPending(new PendingRefundSpec(
                payment.getBooking(), payment, appliedReceipt, RefundReason.VOLUNTARY_GRACE,
                RefundBasisType.BOOKING_CANCELLATION, UUID.randomUUID(), NOW.minusSeconds(10), actor)));

        UUID key = UUID.randomUUID();
        var attempt = RefundAttempt.start(new PendingRefundAttemptSpec(refund, 1, RefundExecutionMode.SIMULATOR,
                key, "a".repeat(64), "refund:" + key, RefundExecutionTrigger.OWNER, actor, NOW.minusSeconds(5)));
        attempt.completeFailed("SIM_ERR", "Transient failure", NOW.minusSeconds(4), NOW.minusSeconds(4));
        attempts.saveAndFlush(attempt);
        refund.requireOwnerAction();
        refunds.saveAndFlush(refund);

        em.clear();

        var summary = finance.summary();
        assertThat(summary.grossVnd()).isEqualTo(120000);
        assertThat(summary.refundedVnd()).isZero();
        assertThat(summary.netVnd()).isEqualTo(120000); // PENDING does not reduce net
        assertThat(summary.pendingRefundVnd()).isEqualTo(120000);

        var list = finance.list(1, 10).getContent();
        assertThat(list).hasSize(1);
        assertThat(list.getFirst().collectedAmount()).isEqualTo(120000);
        assertThat(list.getFirst().refundedAmount()).isZero();
        assertThat(list.getFirst().netRecordedAmount()).isEqualTo(120000);
        assertThat(list.getFirst().pendingRefundAmount()).isEqualTo(120000);
    }

    @Test
    void concurrentConsistencyWithTwoRealPostgresTransactions() throws Exception {
        // Other connections must see the baseline, unlike the usual rollback-only test fixture.
        TestTransaction.flagForCommit();
        TestTransaction.end();
        CountDownLatch t1Started = new CountDownLatch(1);
        CountDownLatch t2Committed = new CountDownLatch(1);
        AtomicReference<OwnerFinanceSummaryResponse> before = new AtomicReference<>();
        AtomicReference<OwnerFinanceSummaryResponse> after = new AtomicReference<>();
        AtomicReference<Throwable> t1Error = new AtomicReference<>();

        Thread t1 = new Thread(() -> {
            TransactionTemplate tt = new TransactionTemplate(txManager);
            tt.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            tt.setReadOnly(true);
            try {
                tt.execute(status -> {
                    // T1 reads summary at t1
                    before.set(finance.summary());
                    t1Started.countDown();
                    try {
                        // Wait for T2 to commit on another connection
                        assertThat(t2Committed.await(15, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    // T1 reads summary again in the SAME transaction at t2
                    after.set(finance.summary());
                    return null;
                });
            } catch (Throwable t) {
                t1Error.set(t);
            }
        });

        try {
            t1.start();
            assertThat(t1Started.await(15, TimeUnit.SECONDS)).isTrue();
            TransactionTemplate tt2 = new TransactionTemplate(txManager);
            tt2.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tt2.execute(status -> {
                var payment = payments.findById(paymentA).orElseThrow();
                var receipt = receipts.findByPaymentIdOrderByReceivedAtAscIdAsc(paymentA).getFirst();
                var actor = users.findById(ownerA).orElseThrow();
                var refund = refunds.saveAndFlush(Refund.createPending(new PendingRefundSpec(
                        payment.getBooking(), payment, receipt, RefundReason.VOLUNTARY_GRACE,
                        RefundBasisType.BOOKING_CANCELLATION, UUID.randomUUID(), NOW.minusSeconds(10), actor)));
                UUID key = UUID.randomUUID();
                var attempt = RefundAttempt.start(new PendingRefundAttemptSpec(refund, 1,
                        RefundExecutionMode.SIMULATOR, key, "c".repeat(64), "refund:" + key,
                        RefundExecutionTrigger.OWNER, actor, NOW.minusSeconds(5)));
                attempt.completeSucceeded("SIM_CONCURRENT", "TRANSFER_CONCURRENT", NOW.minusSeconds(4));
                attempts.saveAndFlush(attempt);
                refund.completeWith(attempt, NOW.minusSeconds(4));
                refunds.saveAndFlush(refund);
                payment.recordFullRefund(BigDecimal.valueOf(120000));
                payments.saveAndFlush(payment);
                return null;
            });
            t2Committed.countDown();
            t1.join(15000);
            assertThat(t1.isAlive()).isFalse();
            assertThat(t1Error.get()).isNull();
            assertThat(before.get()).isNotNull();
            assertThat(before.get().netVnd()).isEqualTo(120000);
            assertThat(after.get()).isEqualTo(before.get());
            // A new service transaction sees T2's committed full refund.
            var fresh = finance.summary();
            assertThat(fresh.grossVnd()).isEqualTo(120000);
            assertThat(fresh.refundedVnd()).isEqualTo(120000);
            assertThat(fresh.netVnd()).isZero();
        } finally {
            t2Committed.countDown();
            t1.join(15000);
            if (t1.isAlive()) t1.interrupt();
            // Only the disposable Testcontainers database; removes the explicitly committed fixture.
            jdbc.execute("TRUNCATE TABLE user_profile CASCADE");
            TestTransaction.start();
        }
    }
}
