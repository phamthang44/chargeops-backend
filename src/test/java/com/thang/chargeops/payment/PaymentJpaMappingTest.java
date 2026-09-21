package com.thang.chargeops.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.common.enums.PaymentApplicationClassification;
import com.thang.chargeops.common.enums.PaymentEnvironment;
import com.thang.chargeops.common.enums.PaymentMethod;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.payment.entity.Payment;
import com.thang.chargeops.payment.entity.PaymentTransaction;
import com.thang.chargeops.payment.model.NormalizedReceipt;
import com.thang.chargeops.payment.model.PendingPaymentSpec;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.docker.compose.enabled=false"
})
@AutoConfigureTestDatabase(replace = NONE)
class PaymentJpaMappingTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                    .asCompatibleSubstituteFor("postgres")
    );

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentTransactionRepository transactionRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private UUID driverId;
    private UUID connectorId;

    @BeforeEach
    void setUpBaseData() {
        if (jdbcTemplate.queryForObject("SELECT count(*) FROM user_profile WHERE keycloak_id = 'jpa-test-driver'", Integer.class) == 0) {
            jdbcTemplate.execute("INSERT INTO user_profile(keycloak_id, email) VALUES ('jpa-test-driver', 'driver@example.test')");
        }
        driverId = jdbcTemplate.queryForObject(
                "SELECT id FROM user_profile WHERE keycloak_id = 'jpa-test-driver'", UUID.class
        );

        if (jdbcTemplate.queryForObject("SELECT count(*) FROM stations WHERE name = 'JPA Station'", Integer.class) == 0) {
            jdbcTemplate.update("""
                    INSERT INTO stations(owner_id, name, address_line, contact_phone, status)
                    VALUES (?, 'JPA Station', 'Test Address', '0123456789', 'ACTIVE')
                    """, driverId);
        }
        UUID stationId = jdbcTemplate.queryForObject(
                "SELECT id FROM stations WHERE name = 'JPA Station'", UUID.class
        );

        if (jdbcTemplate.queryForObject("SELECT count(*) FROM charge_points WHERE charge_point_code = 'CP-JPA-1'", Integer.class) == 0) {
            jdbcTemplate.update("""
                    INSERT INTO charge_points(station_id, charge_point_code, provisioning_status, operational_status)
                    VALUES (?, 'CP-JPA-1', 'PROVISIONED', 'OPERATING')
                    """, stationId);
        }
        UUID chargePointId = jdbcTemplate.queryForObject(
                "SELECT id FROM charge_points WHERE charge_point_code = 'CP-JPA-1'", UUID.class
        );

        if (jdbcTemplate.queryForObject("SELECT count(*) FROM connectors WHERE connector_code = 'CON-1'", Integer.class) == 0) {
            jdbcTemplate.update("""
                    INSERT INTO connectors(charge_point_id, connector_code, connector_type, power_kw, charger_type, runtime_status)
                    VALUES (?, 'CON-1', 'CCS2', 60.0, 'DC', 'AVAILABLE')
                    """, chargePointId);
        }
        connectorId = jdbcTemplate.queryForObject(
                "SELECT id FROM connectors WHERE connector_code = 'CON-1'", UUID.class
        );
    }

    private Booking createTestBooking() {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO bookings(id, driver_id, connector_id, start_at, end_at, status, total_amount,
                                     station_name_snapshot, station_address_snapshot,
                                     charge_point_code_snapshot, connector_code_snapshot, expires_at)
                VALUES (?, ?, ?, '2026-09-12 10:00Z'::timestamptz, '2026-09-12 11:00Z'::timestamptz,
                        'PENDING', 120000.00, 'JPA Station', 'Test Address', 'CP-JPA-1', 'CON-1',
                        '2026-09-12 09:10Z'::timestamptz)
                """, bookingId, driverId, connectorId);
        return bookingRepository.findById(bookingId).orElseThrow();
    }

    @Test
    @DisplayName("1. Hibernate validate: persist, flush, clear, and reload Payment and PaymentTransaction with JSONB")
    void persistAndReload_roundTripWithJsonb() throws Exception {
        Booking booking = createTestBooking();

        PendingPaymentSpec spec = new PendingPaymentSpec(
                booking,
                new BigDecimal("120000.00"),
                PaymentMethod.BANK_TRANSFER,
                "SEPAY",
                "ACC-VN-1",
                "VND"
        );
        Payment payment = Payment.createPending(spec);
        payment = paymentRepository.saveAndFlush(payment);

        NormalizedReceipt receipt = new NormalizedReceipt(
                "SEPAY",
                "ACC-VN-1",
                "TX-SEPAY-001",
                new BigDecimal("120000.00"),
                "VND",
                Instant.parse("2026-09-12T09:50:00Z"),
                Instant.parse("2026-09-12T09:50:02Z"),
                "VA001",
                "BK-9999",
                "Payment for booking BK-9999",
                "{\"gateway\":\"SEPAY\",\"accountNumber\":\"ACC-VN-1\",\"transferAmount\":120000}"
        );
        PaymentTransaction tx = PaymentTransaction.create(payment, receipt);
        tx = transactionRepository.saveAndFlush(tx);

        entityManager.clear();

        Payment reloadedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(reloadedPayment.getAmount()).isEqualByComparingTo(new BigDecimal("120000.00"));
        assertThat(reloadedPayment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(reloadedPayment.getProvider()).isEqualTo("SEPAY");
        assertThat(reloadedPayment.getReceivingAccountRef()).isEqualTo("ACC-VN-1");
        assertThat(reloadedPayment.getCurrency()).isEqualTo("VND");
        assertThat(reloadedPayment.getVersion()).isEqualTo(0L);
        assertThat(reloadedPayment.isNeedsReconciliation()).isFalse();
        assertThat(reloadedPayment.getPaymentCode()).isEqualTo(payment.getPaymentCode());
        assertThat(paymentRepository.findByPaymentCode(payment.getPaymentCode())).isPresent();

        PaymentTransaction reloadedTx = transactionRepository.findById(tx.getId()).orElseThrow();
        assertThat(reloadedTx.getTransactionRef()).isEqualTo("TX-SEPAY-001");
        assertThat(reloadedTx.getPayment().getId()).isEqualTo(reloadedPayment.getId());
        assertThat(reloadedTx.getApplicationClassification()).isEqualTo(PaymentApplicationClassification.UNAPPLIED);

        // JSONB structure round-trip check
        JsonNode payloadNode = objectMapper.readTree(reloadedTx.getRawPayload());
        assertThat(payloadNode.get("gateway").asText()).isEqualTo("SEPAY");
        assertThat(payloadNode.get("transferAmount").asLong()).isEqualTo(120000L);
    }

    @Test
    @DisplayName("2. Legacy payment with NULL projections loads without coercing NULL to zero")
    void legacyPayment_preservesNulls() {
        Booking booking = createTestBooking();

        UUID legacyPaymentId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO payments(id, booking_id, amount, status, method, gateway_txn_ref, refund_amount,
                                     paid_at, provider, receiving_account_ref, currency, collected_amount,
                                     applied_to_package_amount, package_refunded_amount, excess_amount,
                                     unallocated_amount, needs_reconciliation, environment, version)
                VALUES (?, ?, 120000.00, 'PAID', 'VNPAY', 'legacy-vnpay-ref', 120000.00,
                        now(), NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, true, 'LEGACY', 0)
                """, legacyPaymentId, booking.getId());

        entityManager.clear();

        Payment legacyPayment = paymentRepository.findById(legacyPaymentId).orElseThrow();
        assertThat(legacyPayment.getStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(legacyPayment.getMethod()).isEqualTo(PaymentMethod.VNPAY);
        assertThat(legacyPayment.getGatewayTxnRef()).isEqualTo("legacy-vnpay-ref");
        assertThat(legacyPayment.getRefundAmount()).isEqualByComparingTo(new BigDecimal("120000.00"));
        assertThat(legacyPayment.getEnvironment()).isEqualTo(PaymentEnvironment.LEGACY);
        assertThat(legacyPayment.isNeedsReconciliation()).isTrue();

        // Must stay NULL, NOT coerced to zero
        assertThat(legacyPayment.getProvider()).isNull();
        assertThat(legacyPayment.getReceivingAccountRef()).isNull();
        assertThat(legacyPayment.getCurrency()).isNull();
        assertThat(legacyPayment.getPaymentCode()).isNull();
        assertThatThrownBy(legacyPayment::markFailed).isInstanceOf(com.thang.chargeops.exception.AppException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT collected_amount FROM payments WHERE id = ?", BigDecimal.class, legacyPaymentId)).isNull();
    }

    @Test
    @DisplayName("3. Unmatched receipt allows null payment_id and can be found by composite identity")
    void unmatchedReceipt_allowsNullPaymentId() {
        NormalizedReceipt receipt = new NormalizedReceipt(
                "SEPAY",
                "ACC-VN-2",
                "TX-UNMATCHED-001",
                new BigDecimal("50000.00"),
                "VND",
                Instant.parse("2026-09-12T10:00:00Z"),
                Instant.parse("2026-09-12T10:00:01Z"),
                null,
                null,
                "Unknown transfer",
                "{}"
        );
        PaymentTransaction tx = PaymentTransaction.create(null, receipt);
        transactionRepository.saveAndFlush(tx);

        entityManager.clear();

        PaymentTransaction reloaded = transactionRepository
                .findByProviderAndReceivingAccountRefAndTransactionRef("SEPAY", "ACC-VN-2", "TX-UNMATCHED-001")
                .orElseThrow();
        assertThat(reloaded.getPayment()).isNull();
        assertThat(reloaded.getApplicationClassification()).isEqualTo(PaymentApplicationClassification.UNAPPLIED);
        assertThat(reloaded.getAmount()).isEqualByComparingTo(new BigDecimal("50000.00"));
    }

    @Test
    @DisplayName("4. Composite unique constraint (provider, receiving_account_ref, transaction_ref) triggers DataIntegrityViolationException on flush")
    void compositeUniqueConstraint_enforced() {
        NormalizedReceipt receipt1 = new NormalizedReceipt(
                "SEPAY", "ACC-DUP", "TX-DUP-1", new BigDecimal("100000.00"), "VND",
                null, Instant.now(), null, null, null, null
        );
        transactionRepository.saveAndFlush(PaymentTransaction.create(null, receipt1));

        NormalizedReceipt receipt2 = new NormalizedReceipt(
                "SEPAY", "ACC-DUP", "TX-DUP-1", new BigDecimal("200000.00"), "VND",
                null, Instant.now(), null, null, null, null
        );
        PaymentTransaction duplicate = PaymentTransaction.create(null, receipt2);
        transactionRepository.save(duplicate);

        assertThatThrownBy(() -> transactionRepository.flush())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @DisplayName("5. Optimistic locking: concurrent update on stale version fails with OptimisticLockingFailureException")
    void optimisticLocking_failsOnStaleVersion() {
        Booking booking = createTestBooking();
        PendingPaymentSpec spec = new PendingPaymentSpec(
                booking, new BigDecimal("120000.00"), PaymentMethod.BANK_TRANSFER, "SEPAY", "ACC-OPT", "VND"
        );
        Payment payment = paymentRepository.saveAndFlush(Payment.createPending(spec));
        UUID paymentId = payment.getId();

        TransactionTemplate tx1 = new TransactionTemplate(transactionManager);
        TransactionTemplate tx2 = new TransactionTemplate(transactionManager);

        tx1.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx2.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // Tx1 loads and records receipt
        tx1.execute(status -> {
            Payment p1 = paymentRepository.findById(paymentId).orElseThrow();
            p1.bindOrder(new com.thang.chargeops.payment.model.OrderCheckout("order-opt", p1.getPaymentCode(), "VAOPT", p1.getAmount(), Instant.parse("2026-09-12T09:10:00Z"), null, null), Instant.parse("2026-09-12T09:00:00Z"));
            paymentRepository.saveAndFlush(p1);
            return null;
        });

        // Tx2 attempts to update stale version (simulated by setting stale version or concurrent edit)
        assertThatThrownBy(() -> tx2.execute(status -> {
            Payment p2 = paymentRepository.findById(paymentId).orElseThrow();
            // Stale check by updating direct version back in DB
            jdbcTemplate.update("UPDATE payments SET version = version + 1 WHERE id = ?", paymentId);
            p2.markFailed();
            paymentRepository.saveAndFlush(p2);
            return null;
        })).isInstanceOf(OptimisticLockingFailureException.class);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @DisplayName("6. Pessimistic lock holds row until transaction commit/rollback")
    void pessimisticLock_holdsRowUntilCommit() throws Exception {
        Booking booking = createTestBooking();
        PendingPaymentSpec spec = new PendingPaymentSpec(
                booking, new BigDecimal("120000.00"), PaymentMethod.BANK_TRANSFER, "SEPAY", "ACC-LOCK", "VND"
        );
        Payment payment = paymentRepository.saveAndFlush(Payment.createPending(spec));
        UUID paymentId = payment.getId();

        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        CountDownLatch thread2Completed = new CountDownLatch(1);

        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
        txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Thread 1 acquires lock and holds it
        executor.submit(() -> {
            txTemplate.execute(status -> {
                paymentRepository.findByIdWithLock(paymentId);
                lockAcquired.countDown();
                try {
                    releaseLock.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
        });

        assertThat(lockAcquired.await(3, TimeUnit.SECONDS)).isTrue();

        long startTime = System.currentTimeMillis();

        // Thread 2 tries to acquire lock while Thread 1 is holding it
        Future<?> thread2Future = executor.submit(() -> {
            txTemplate.execute(status -> {
                paymentRepository.findByIdWithLock(paymentId);
                thread2Completed.countDown();
                return null;
            });
        });

        // Thread 2 must still be waiting
        assertThat(thread2Completed.await(300, TimeUnit.MILLISECONDS)).isFalse();

        // Release Thread 1
        releaseLock.countDown();

        // Thread 2 should now finish
        thread2Future.get(5, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - startTime;
        assertThat(elapsed).isGreaterThanOrEqualTo(300);

        executor.shutdownNow();
    }

    @Test
    @DisplayName("7. Repositories support pagination and stable ordering")
    void repositories_supportPaginationAndSorting() {
        Booking booking = createTestBooking();
        Payment payment = paymentRepository.saveAndFlush(Payment.createPending(
                new PendingPaymentSpec(booking, new BigDecimal("120000.00"), PaymentMethod.BANK_TRANSFER, "SEPAY", "ACC-PAG", "VND")
        ));

        for (int i = 1; i <= 5; i++) {
            NormalizedReceipt receipt = new NormalizedReceipt(
                    "SEPAY", "ACC-PAG", "TX-PAG-" + i, new BigDecimal("10000.00"), "VND",
                    Instant.parse("2026-09-12T10:00:0" + i + "Z"),
                    Instant.parse("2026-09-12T10:00:0" + i + "Z"),
                    "VA001",
                    "CODE-ABC", "Content " + i, null
            );
            transactionRepository.save(PaymentTransaction.create(payment, receipt));
        }
        transactionRepository.flush();

        Page<PaymentTransaction> page0 = transactionRepository.findByPaymentId(payment.getId(), PageRequest.of(0, 3));
        assertThat(page0.getTotalElements()).isEqualTo(5);
        assertThat(page0.getContent()).hasSize(3);

        List<PaymentTransaction> ordered = transactionRepository.findByPaymentIdOrderByReceivedAtAscIdAsc(payment.getId());
        assertThat(ordered).hasSize(5);
        assertThat(ordered.get(0).getTransactionRef()).isEqualTo("TX-PAG-1");
        assertThat(ordered.get(4).getTransactionRef()).isEqualTo("TX-PAG-5");

        Page<PaymentTransaction> byCode = transactionRepository.findByPaymentCode("CODE-ABC", PageRequest.of(0, 10));
        assertThat(byCode.getTotalElements()).isEqualTo(5);
    }
}
