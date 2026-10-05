package com.thang.chargeops.refund;

import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.RefundErrorCode;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.refund.dto.request.ExecuteRefundRequest;
import com.thang.chargeops.refund.dto.request.RefundExecutionOutcome;
import com.thang.chargeops.refund.dto.response.RefundDetailResponse;
import com.thang.chargeops.refund.executor.ManualRecordRefundExecutor;
import com.thang.chargeops.refund.executor.RefundExecutorRegistry;
import com.thang.chargeops.refund.executor.SimulatorRefundExecutor;
import com.thang.chargeops.refund.model.RefundExecutionMode;
import com.thang.chargeops.refund.model.RefundStatus;
import com.thang.chargeops.refund.repository.RefundAttemptRepository;
import com.thang.chargeops.refund.repository.RefundRepository;
import com.thang.chargeops.refund.service.AdminRefundService;
import com.thang.chargeops.refund.service.RefundDetailAssembler;
import com.thang.chargeops.refund.service.RefundExecutionResultHandler;
import com.thang.chargeops.refund.service.impl.AdminRefundServiceImpl;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.docker.compose.enabled=false"
})
@AutoConfigureTestDatabase(replace = NONE)
@ActiveProfiles("test")
@Import({
        AdminRefundServiceImpl.class,
        RefundExecutorRegistry.class,
        SimulatorRefundExecutor.class,
        ManualRecordRefundExecutor.class,
        RefundDetailAssembler.class,
        RefundExecutionResultHandler.class,
        AdminRefundExecutionPostgresTest.TestConfig.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminRefundExecutionPostgresTest {

    private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");
    private static final BigDecimal PACKAGE_AMOUNT = new BigDecimal("120000.00");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                    .asCompatibleSubstituteFor("postgres")
    );

    @TestConfiguration
    static class TestConfig {
        static volatile UUID currentAdminId;

        @Bean
        Clock applicationClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        CurrentProfileProvider currentProfileProvider(UserProfileRepository userProfileRepository) {
            CurrentProfileProvider provider = org.mockito.Mockito.mock(CurrentProfileProvider.class);
            org.mockito.Mockito.when(provider.requireProfile()).thenAnswer(inv ->
                    userProfileRepository.findById(currentAdminId)
                            .orElseThrow(() -> new IllegalStateException("Current admin profile not set: " + currentAdminId)));
            return provider;
        }
    }

    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AdminRefundService service;
    @Autowired private RefundRepository refundRepository;
    @Autowired private RefundAttemptRepository refundAttemptRepository;

    private UUID adminId;

    @BeforeEach
    void setUpAdmin() {
        String adminKey = "adm-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.execute("""
                INSERT INTO user_profile(keycloak_id, email, display_name)
                VALUES ('%1$s', '%1$s@chargeops.test', 'Admin %1$s');
                """.formatted(adminKey));
        adminId = jdbc.queryForObject("SELECT id FROM user_profile WHERE keycloak_id=?", UUID.class, adminKey);
        TestConfig.currentAdminId = adminId;
    }

    @Test
    void concurrentSameRequestKeyCreatesExactlyOneAttemptAndIdenticalResponse() throws Exception {
        Fixture fixture = seedPaidRefundFixture();
        UUID requestKey = UUID.randomUUID();
        ExecuteRefundRequest request = new ExecuteRefundRequest(
                0L,
                RefundExecutionMode.SIMULATOR,
                RefundExecutionOutcome.SUCCEEDED,
                null,
                null,
                "Concurrent execute same requestKey"
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<RefundDetailResponse> first = pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return executeInTransaction(fixture.refundId(), requestKey, request);
            });
            Future<RefundDetailResponse> second = pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return executeInTransaction(fixture.refundId(), requestKey, request);
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            RefundDetailResponse resp1 = first.get(30, TimeUnit.SECONDS);
            RefundDetailResponse resp2 = second.get(30, TimeUnit.SECONDS);

            assertThat(resp1).isNotNull();
            assertThat(resp2).isNotNull();
            assertThat(resp1.status()).isEqualTo(RefundStatus.SUCCEEDED);
            assertThat(resp2.status()).isEqualTo(RefundStatus.SUCCEEDED);
            assertThat(resp1.version()).isEqualTo(1L);
            assertThat(resp2.version()).isEqualTo(1L);
            assertThat(resp1.successfulAttemptId()).isNotNull();
            assertThat(resp1.successfulAttemptId()).isEqualTo(resp2.successfulAttemptId());
            assertThat(resp1.transferReference()).startsWith("SIM-REF-");
            assertThat(resp1.transferReference()).isEqualTo(resp2.transferReference());
        }

        // Database assertions: exactly one attempt created, refund succeeded, payment refunded
        long attemptCount = countRows("refund_attempts", "refund_id", fixture.refundId());
        assertThat(attemptCount).isEqualTo(1L);

        String refundStatus = jdbc.queryForObject(
                "SELECT status FROM refunds WHERE id = ?", String.class, fixture.refundId());
        Long refundVersion = jdbc.queryForObject(
                "SELECT version FROM refunds WHERE id = ?", Long.class, fixture.refundId());
        UUID successfulAttemptId = jdbc.queryForObject(
                "SELECT successful_attempt_id FROM refunds WHERE id = ?", UUID.class, fixture.refundId());
        assertThat(refundStatus).isEqualTo("SUCCEEDED");
        assertThat(refundVersion).isEqualTo(1L);
        assertThat(successfulAttemptId).isNotNull();

        String paymentStatus = jdbc.queryForObject(
                "SELECT status FROM payments WHERE id = ?", String.class, fixture.paymentId());
        BigDecimal paymentRefundAmount = jdbc.queryForObject(
                "SELECT refund_amount FROM payments WHERE id = ?", BigDecimal.class, fixture.paymentId());
        assertThat(paymentStatus).isEqualTo(PaymentStatus.REFUNDED.name());
        assertThat(paymentRefundAmount).isEqualByComparingTo(PACKAGE_AMOUNT);
    }

    @Test
    void concurrentDifferentRequestKeyAllowsOnlyOneWinnerAndRejectsSecond() throws Exception {
        Fixture fixture = seedPaidRefundFixture();
        UUID requestKeyA = UUID.randomUUID();
        UUID requestKeyB = UUID.randomUUID();

        ExecuteRefundRequest requestA = new ExecuteRefundRequest(
                0L,
                RefundExecutionMode.SIMULATOR,
                RefundExecutionOutcome.SUCCEEDED,
                null,
                null,
                "Request A"
        );
        ExecuteRefundRequest requestB = new ExecuteRefundRequest(
                0L,
                RefundExecutionMode.SIMULATOR,
                RefundExecutionOutcome.SUCCEEDED,
                null,
                null,
                "Request B"
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<RefundDetailResponse> futureA = pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return executeInTransaction(fixture.refundId(), requestKeyA, requestA);
            });
            Future<RefundDetailResponse> futureB = pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return executeInTransaction(fixture.refundId(), requestKeyB, requestB);
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<RefundDetailResponse> successes = new ArrayList<>();
            List<Throwable> errors = new ArrayList<>();

            for (Future<RefundDetailResponse> f : List.of(futureA, futureB)) {
                try {
                    successes.add(f.get(30, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    errors.add(e.getCause());
                }
            }

            assertThat(successes).hasSize(1);
            assertThat(errors).hasSize(1);
            assertThat(errors.getFirst()).isInstanceOf(AppException.class);
            AppException appEx = (AppException) errors.getFirst();
            assertThat(appEx.getErrorCode()).isIn(
                    RefundErrorCode.VERSION_CONFLICT,
                    RefundErrorCode.EXECUTION_CONFLICT
            );
        }

        // Database assertions: exactly one attempt created, no double refund
        long attemptCount = countRows("refund_attempts", "refund_id", fixture.refundId());
        assertThat(attemptCount).isEqualTo(1L);

        BigDecimal paymentRefundAmount = jdbc.queryForObject(
                "SELECT refund_amount FROM payments WHERE id = ?", BigDecimal.class, fixture.paymentId());
        assertThat(paymentRefundAmount).isEqualByComparingTo(PACKAGE_AMOUNT);
    }

    @Test
    void simulatorFailureOutcomeLeavesRefundPendingAndPermitsRetryWithNewAttempt() {
        Fixture fixture = seedPaidRefundFixture();
        UUID requestKey1 = UUID.randomUUID();
        ExecuteRefundRequest failRequest = new ExecuteRefundRequest(
                0L,
                RefundExecutionMode.SIMULATOR,
                RefundExecutionOutcome.FAILED,
                null,
                null,
                "Simulated temporary failure"
        );

        RefundDetailResponse failResponse = executeInTransaction(fixture.refundId(), requestKey1, failRequest);
        assertThat(failResponse.status()).isEqualTo(RefundStatus.PENDING);
        assertThat(failResponse.version()).isEqualTo(1L);
        assertThat(failResponse.successfulAttemptId()).isNull();
        assertThat(failResponse.attempts()).hasSize(1);
        assertThat(failResponse.attempts().getFirst().failureCode()).isEqualTo("SIMULATED_FAILURE");

        // Verify DB: Refund still PENDING, Payment still PAID, attempt count = 1
        String refundStatus = jdbc.queryForObject(
                "SELECT status FROM refunds WHERE id = ?", String.class, fixture.refundId());
        assertThat(refundStatus).isEqualTo("PENDING");
        String paymentStatus = jdbc.queryForObject(
                "SELECT status FROM payments WHERE id = ?", String.class, fixture.paymentId());
        assertThat(paymentStatus).isEqualTo("PAID");
        assertThat(countRows("refund_attempts", "refund_id", fixture.refundId())).isEqualTo(1L);

        // Subsequent retry with new requestKey and SUCCEEDED outcome
        UUID requestKey2 = UUID.randomUUID();
        ExecuteRefundRequest succeedRequest = new ExecuteRefundRequest(
                1L,
                RefundExecutionMode.SIMULATOR,
                RefundExecutionOutcome.SUCCEEDED,
                null,
                null,
                "Retry after failure succeeds"
        );

        RefundDetailResponse succeedResponse = executeInTransaction(fixture.refundId(), requestKey2, succeedRequest);
        assertThat(succeedResponse.status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(succeedResponse.version()).isEqualTo(2L);
        assertThat(succeedResponse.successfulAttemptId()).isNotNull();
        assertThat(succeedResponse.attempts()).hasSize(2);
        assertThat(succeedResponse.attempts().get(0).sequenceNo()).isEqualTo(1);
        assertThat(succeedResponse.attempts().get(1).sequenceNo()).isEqualTo(2);

        // Verify DB: 2 attempts, refund SUCCEEDED, payment REFUNDED
        assertThat(countRows("refund_attempts", "refund_id", fixture.refundId())).isEqualTo(2L);
        String finalRefundStatus = jdbc.queryForObject(
                "SELECT status FROM refunds WHERE id = ?", String.class, fixture.refundId());
        assertThat(finalRefundStatus).isEqualTo("SUCCEEDED");
        String finalPaymentStatus = jdbc.queryForObject(
                "SELECT status FROM payments WHERE id = ?", String.class, fixture.paymentId());
        assertThat(finalPaymentStatus).isEqualTo("REFUNDED");
    }

    @Test
    void sameRequestKeyWithModifiedPayloadThrowsRequestConflict() {
        Fixture fixture = seedPaidRefundFixture();
        UUID requestKey = UUID.randomUUID();
        ExecuteRefundRequest original = new ExecuteRefundRequest(
                0L,
                RefundExecutionMode.SIMULATOR,
                RefundExecutionOutcome.FAILED,
                null,
                null,
                "Original note"
        );
        executeInTransaction(fixture.refundId(), requestKey, original);

        ExecuteRefundRequest modified = new ExecuteRefundRequest(
                0L,
                RefundExecutionMode.SIMULATOR,
                RefundExecutionOutcome.FAILED,
                null,
                null,
                "Modified note triggers payload hash mismatch"
        );

        assertThatThrownBy(() -> executeInTransaction(fixture.refundId(), requestKey, modified))
                .isInstanceOfSatisfying(AppException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(RefundErrorCode.REQUEST_CONFLICT));
    }

    @Test
    void manualRecordExecutorCreatesTerminalSucceededAttempt() {
        Fixture fixture = seedPaidRefundFixture();
        UUID requestKey = UUID.randomUUID();
        Instant performedAt = NOW.minusSeconds(120);
        ExecuteRefundRequest request = new ExecuteRefundRequest(
                0L,
                RefundExecutionMode.MANUAL_RECORD,
                RefundExecutionOutcome.SUCCEEDED,
                "EXT-TXN-987654",
                performedAt,
                "Thủ công qua chuyển khoản ngân hàng"
        );

        RefundDetailResponse response = executeInTransaction(fixture.refundId(), requestKey, request);

        assertThat(response.status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(response.transferReference()).isEqualTo("EXT-TXN-987654");
        assertThat(response.attempts()).hasSize(1);
        assertThat(response.attempts().getFirst().executionMode()).isEqualTo(RefundExecutionMode.MANUAL_RECORD);
        assertThat(response.attempts().getFirst().transferReference()).isEqualTo("EXT-TXN-987654");

        // Verify DB
        assertThat(countRows("refund_attempts", "refund_id", fixture.refundId())).isEqualTo(1L);
        String dbTransferRef = jdbc.queryForObject(
                "SELECT transfer_reference FROM refund_attempts WHERE refund_id = ?",
                String.class, fixture.refundId());
        assertThat(dbTransferRef).isEqualTo("EXT-TXN-987654");
    }

    private RefundDetailResponse executeInTransaction(UUID refundId, UUID requestKey, ExecuteRefundRequest request) {
        return new TransactionTemplate(transactionManager).execute(status ->
                service.execute(refundId, requestKey, request));
    }

    private long countRows(String table, String column, UUID value) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Long.class,
                value
        );
    }

    private record Fixture(UUID bookingId, UUID paymentId, UUID refundId) {}

    private Fixture seedPaidRefundFixture() {
        String key = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String userKey = "rf-dr-" + key;
        String point = "CP-RF-" + key;
        String connector = "C-RF-" + key;
        String code = "RF" + key.toUpperCase();

        jdbc.execute("""
                INSERT INTO user_profile(keycloak_id, email, display_name)
                VALUES ('%1$s', '%1$s@example.test', 'Driver %1$s');
                INSERT INTO stations(owner_id, name, address_line, contact_phone, status)
                SELECT id, 'RF station %2$s', 'Test address', '0901234567', 'ACTIVE'
                FROM user_profile WHERE keycloak_id='%1$s';
                INSERT INTO charge_points(station_id, charge_point_code, provisioning_status, operational_status)
                SELECT id, '%3$s', 'PROVISIONED', 'OPERATING'
                FROM stations WHERE name='RF station %2$s';
                INSERT INTO connectors(charge_point_id, connector_code, connector_type, power_kw, charger_type, runtime_status)
                SELECT id, '%4$s', 'CCS2', 60, 'DC', 'AVAILABLE'
                FROM charge_points WHERE charge_point_code='%3$s';
                INSERT INTO bookings(booking_code, driver_id, connector_id, start_at, end_at, status, total_amount,
                    station_name_snapshot, station_address_snapshot, charge_point_code_snapshot,
                    connector_code_snapshot, expires_at)
                SELECT 'BK-%2$s', u.id, c.id, '2026-09-26 10:00Z', '2026-09-26 11:00Z', 'CONFIRMED', 120000,
                    'RF station %2$s', 'Test address', '%3$s', '%4$s', '2026-09-26 09:10Z'
                FROM user_profile u CROSS JOIN connectors c
                WHERE u.keycloak_id='%1$s' AND c.connector_code='%4$s';
                INSERT INTO payments(booking_id, amount, status, method, refund_amount, paid_at, provider,
                    receiving_account_ref, currency, needs_reconciliation, environment, version, payment_code)
                SELECT id, 120000, 'PAID', 'SIMULATOR', 0, now(), 'SIMULATOR', 'SIMULATOR',
                    'VND', false, 'SIMULATOR', 0, '%5$s'
                FROM bookings WHERE station_name_snapshot='RF station %2$s';
                INSERT INTO payment_transactions(payment_id, provider, receiving_account_ref, transaction_ref,
                    amount, currency, received_at, application_classification, payment_code, va_number, version)
                SELECT id, 'SIMULATOR', 'SIMULATOR', 'TX-%5$s', amount, 'VND', now(),
                    'APPLIED', payment_code, 'SIM-VA-%5$s', 0
                FROM payments WHERE payment_code='%5$s';
                """.formatted(userKey, key, point, connector, code));

        UUID bookingId = jdbc.queryForObject("SELECT id FROM bookings WHERE station_name_snapshot=?", UUID.class, "RF station " + key);
        UUID paymentId = jdbc.queryForObject("SELECT id FROM payments WHERE payment_code=?", UUID.class, code);
        UUID sourceTxId = jdbc.queryForObject("SELECT id FROM payment_transactions WHERE transaction_ref=?", UUID.class, "TX-" + code);

        UUID basisId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO refunds(booking_id, payment_id, source_payment_transaction_id,
                    basis_type, basis_id, amount, currency, reason, status,
                    execution_policy, requires_admin_action, decided_by, decision_at, version)
                VALUES (?, ?, ?, 'BOOKING_CANCELLATION', ?, 120000, 'VND', 'VOLUNTARY_GRACE', 'PENDING',
                    'AUTO_FIRST_ATTEMPT', false, ?, ?, 0)
                """, bookingId, paymentId, sourceTxId, basisId, adminId, Timestamp.from(NOW.minusSeconds(300)));

        UUID refundId = jdbc.queryForObject(
                "SELECT id FROM refunds WHERE source_payment_transaction_id = ?", UUID.class, sourceTxId);

        return new Fixture(bookingId, paymentId, refundId);
    }
}
