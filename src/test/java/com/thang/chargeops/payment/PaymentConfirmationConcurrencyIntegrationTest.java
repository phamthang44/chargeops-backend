package com.thang.chargeops.payment;

import com.thang.chargeops.booking.config.BookingPolicyConfig;
import com.thang.chargeops.booking.history.BookingStatusHistoryRecorder;
import com.thang.chargeops.booking.history.BookingStatusHistoryRepository;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.impl.BookingExpirationServiceImpl;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.payment.model.NormalizedReceipt;
import com.thang.chargeops.payment.model.PaymentReceiptResult;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.payment.service.impl.PaymentConfirmationServiceImpl;
import com.thang.chargeops.station.repository.ConnectorRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.docker.compose.enabled=false"
})
@AutoConfigureTestDatabase(replace = NONE)
@Import(BookingStatusHistoryRecorder.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentConfirmationConcurrencyIntegrationTest {

    private static final Instant ACCEPTED_AT = Instant.parse("2026-09-21T03:05:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("120000.00");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                    .asCompatibleSubstituteFor("postgres")
    );

    @Autowired
    private ConnectorRepository connectorRepository;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private PaymentTransactionRepository transactionRepository;
    @Autowired
    private BookingStatusHistoryRepository historyRepository;
    @Autowired
    private BookingStatusHistoryRecorder historyRecorder;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void concurrentCallbacksWithSameReferenceCreateExactlyOneReceipt() throws Exception {
        Fixture fixture = createFixture(ACCEPTED_AT.plusSeconds(300));
        NormalizedReceipt receipt = receipt(
                fixture,
                "TX-SAME-" + UUID.randomUUID(),
                ACCEPTED_AT.minusSeconds(30)
        );
        PaymentConfirmationServiceImpl service = serviceAt(ACCEPTED_AT);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PaymentReceiptResult> first = executor.submit(
                    () -> processAfterBarrier(service, receipt, ready, start));
            Future<PaymentReceiptResult> second = executor.submit(
                    () -> processAfterBarrier(service, receipt, ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<PaymentReceiptResult.Status> statuses = List.of(
                    first.get(10, TimeUnit.SECONDS).status(),
                    second.get(10, TimeUnit.SECONDS).status()
            );

            assertThat(statuses).containsExactlyInAnyOrder(
                    PaymentReceiptResult.Status.APPLIED,
                    PaymentReceiptResult.Status.DUPLICATE
            );
        } finally {
            executor.shutdownNow();
        }

        assertThat(transactionRepository
                .findByProviderAndReceivingAccountRefAndTransactionRef(
                        "SIMULATOR", fixture.accountRef(), receipt.transactionRef()))
                .isPresent();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_transactions WHERE payment_id = ?",
                Long.class,
                fixture.paymentId()
        )).isOne();
        assertThat(bookingRepository.findById(fixture.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(paymentRepository.findById(fixture.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PAID);
        assertThat(historyRepository.findByBooking_IdOrderByOccurredAtAscIdAsc(fixture.bookingId()))
                .hasSize(1);
    }

    @Test
    void providerPaidBeforeExpiryButProcessedAfterExpiryRemainsUnappliedAndSlotIsFree() {
        Instant holdExpiry = ACCEPTED_AT.minusSeconds(1);
        Fixture fixture = createFixture(holdExpiry);
        NormalizedReceipt receipt = receipt(
                fixture,
                "TX-LATE-" + UUID.randomUUID(),
                holdExpiry.minusSeconds(30)
        );

        PaymentReceiptResult result = inNewTransaction(
                () -> serviceAt(ACCEPTED_AT).processReceipt(receipt));

        assertThat(result.status()).isEqualTo(PaymentReceiptResult.Status.UNAPPLIED);
        assertThat(result.reason()).isEqualTo("LATE");
        assertThat(transactionRepository
                .findByProviderAndReceivingAccountRefAndTransactionRef(
                        "SIMULATOR", fixture.accountRef(), receipt.transactionRef())
                .orElseThrow()
                .getApplicationReason()).isEqualTo("LATE");
        assertThat(paymentRepository.findById(fixture.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PENDING);
        assertThat(bookingRepository.findById(fixture.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.PENDING);
        assertThat(bookingRepository.existsOverlappingBooking(
                fixture.connectorId(), fixture.startAt(), fixture.endAt(), ACCEPTED_AT))
                .isFalse();
    }

    @Test
    void confirmationUsesServerAcceptanceTimeAndReplayDoesNotResetIt() {
        Fixture fixture = createFixture(ACCEPTED_AT.plusSeconds(300));
        NormalizedReceipt receipt = receipt(
                fixture,
                "TX-TIME-" + UUID.randomUUID(),
                ACCEPTED_AT.minusSeconds(120)
        );
        PaymentConfirmationServiceImpl service = serviceAt(ACCEPTED_AT);

        PaymentReceiptResult first = inNewTransaction(() -> service.processReceipt(receipt));
        PaymentReceiptResult replay = inNewTransaction(() -> service.processReceipt(receipt));

        assertThat(first.status()).isEqualTo(PaymentReceiptResult.Status.APPLIED);
        assertThat(replay.status()).isEqualTo(PaymentReceiptResult.Status.DUPLICATE);
        assertThat(paymentRepository.findById(fixture.paymentId()).orElseThrow().getPaidAt())
                .isEqualTo(ACCEPTED_AT);
        assertThat(bookingRepository.findById(fixture.bookingId()).orElseThrow()
                .getPaymentConfirmedAt()).isEqualTo(ACCEPTED_AT);
        assertThat(historyRepository.findByBooking_IdOrderByOccurredAtAscIdAsc(fixture.bookingId()))
                .singleElement()
                .satisfies(history -> assertThat(history.getOccurredAt()).isEqualTo(ACCEPTED_AT));
    }

    @Test
    void paymentAcceptedBeforeExpiry_thenExpirationCannotDowngrade() throws Exception {
        Instant holdExpiry = ACCEPTED_AT.plusSeconds(300);
        Fixture fixture = createFixture(holdExpiry);
        NormalizedReceipt receipt = receipt(
                fixture,
                "TX-RACE-PAY-FIRST-" + UUID.randomUUID(),
                ACCEPTED_AT.minusSeconds(30)
        );
        PaymentConfirmationServiceImpl paymentService = serviceAt(ACCEPTED_AT);
        BookingExpirationServiceImpl expirationService = new BookingExpirationServiceImpl(
                connectorRepository,
                bookingRepository,
                paymentRepository,
                historyRecorder
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<PaymentReceiptResult> paymentFuture = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test did not start in time");
                }
                return inNewTransaction(() -> paymentService.processReceipt(receipt));
            });

            Instant evalTime = holdExpiry.plusSeconds(10);
            Future<Boolean> expireFuture = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test did not start in time");
                }
                return inNewTransaction(() ->
                        expirationService.expireIfDue(fixture.bookingId(), fixture.connectorId(), evalTime));
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            PaymentReceiptResult paymentResult = paymentFuture.get(10, TimeUnit.SECONDS);
            Boolean expired = expireFuture.get(10, TimeUnit.SECONDS);

            if (paymentResult.status() == PaymentReceiptResult.Status.APPLIED) {
                assertThat(expired).isFalse();
                assertThat(bookingRepository.findById(fixture.bookingId()).orElseThrow().getStatus())
                        .isEqualTo(BookingStatus.CONFIRMED);
                assertThat(paymentRepository.findById(fixture.paymentId()).orElseThrow().getStatus())
                        .isEqualTo(PaymentStatus.PAID);
            } else {
                assertThat(expired).isTrue();
                assertThat(paymentResult.status()).isEqualTo(PaymentReceiptResult.Status.UNAPPLIED);
                assertThat(paymentResult.reason()).isEqualTo("LATE");
                assertThat(bookingRepository.findById(fixture.bookingId()).orElseThrow().getStatus())
                        .isEqualTo(BookingStatus.EXPIRED);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expirationCommitsFirst_thenLateReceiptIsUnappliedAndDoesNotReviveBooking() {
        Instant holdExpiry = ACCEPTED_AT.minusSeconds(10);
        Fixture fixture = createFixture(holdExpiry);
        NormalizedReceipt receipt = receipt(
                fixture,
                "TX-EXPIRE-FIRST-" + UUID.randomUUID(),
                ACCEPTED_AT.minusSeconds(5)
        );

        BookingExpirationServiceImpl expirationService = new BookingExpirationServiceImpl(
                connectorRepository,
                bookingRepository,
                paymentRepository,
                historyRecorder
        );

        boolean expired = inNewTransaction(() ->
                expirationService.expireIfDue(fixture.bookingId(), fixture.connectorId(), ACCEPTED_AT));
        assertThat(expired).isTrue();
        assertThat(bookingRepository.findById(fixture.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.EXPIRED);

        PaymentReceiptResult result = inNewTransaction(
                () -> serviceAt(ACCEPTED_AT).processReceipt(receipt));

        assertThat(result.status()).isEqualTo(PaymentReceiptResult.Status.UNAPPLIED);
        assertThat(result.reason()).isEqualTo("LATE");
        assertThat(bookingRepository.findById(fixture.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.EXPIRED);
        assertThat(paymentRepository.findById(fixture.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void stationPaused_receiptIsUnappliedWithStationUnavailable() {
        Fixture fixture = createFixture(ACCEPTED_AT.plusSeconds(300));
        inNewTransaction(() -> {
            jdbcTemplate.update("UPDATE stations SET operational_status = 'PAUSED' WHERE id = (SELECT cp.station_id FROM charge_points cp JOIN connectors c ON c.charge_point_id = cp.id WHERE c.id = ?)", fixture.connectorId());
            return null;
        });

        NormalizedReceipt receipt = receipt(
                fixture,
                "TX-UNAVAILABLE-" + UUID.randomUUID(),
                ACCEPTED_AT.minusSeconds(30)
        );

        PaymentReceiptResult result = inNewTransaction(
                () -> serviceAt(ACCEPTED_AT).processReceipt(receipt));

        assertThat(result.status()).isEqualTo(PaymentReceiptResult.Status.UNAPPLIED);
        assertThat(result.reason()).isEqualTo("STATION_UNAVAILABLE");
        assertThat(paymentRepository.findById(fixture.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PENDING);
        assertThat(bookingRepository.findById(fixture.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void hardwareServiceable_confirmsPaymentUnderBR_STA_05() {
        Fixture fixture = createFixture(ACCEPTED_AT.plusSeconds(300));
        NormalizedReceipt receipt = receipt(
                fixture,
                "TX-SERVICEABLE-" + UUID.randomUUID(),
                ACCEPTED_AT.minusSeconds(30)
        );

        PaymentReceiptResult result = inNewTransaction(
                () -> serviceAt(ACCEPTED_AT).processReceipt(receipt));

        assertThat(result.status()).isEqualTo(PaymentReceiptResult.Status.APPLIED);
        assertThat(paymentRepository.findById(fixture.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PAID);
        assertThat(bookingRepository.findById(fixture.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
    }

    private PaymentReceiptResult processAfterBarrier(
            PaymentConfirmationServiceImpl service,
            NormalizedReceipt receipt,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent payment test did not start in time");
        }
        return inNewTransaction(() -> service.processReceipt(receipt));
    }

    private PaymentConfirmationServiceImpl serviceAt(Instant now) {
        return new PaymentConfirmationServiceImpl(
                connectorRepository,
                bookingRepository,
                paymentRepository,
                transactionRepository,
                historyRecorder,
                BookingPolicyConfig.defaults(),
                Clock.fixed(now, ZoneOffset.UTC)
        );
    }

    private NormalizedReceipt receipt(Fixture fixture, String transactionRef, Instant providerPaidAt) {
        return new NormalizedReceipt(
                "SIMULATOR",
                fixture.accountRef(),
                transactionRef,
                AMOUNT,
                "VND",
                providerPaidAt,
                ACCEPTED_AT,
                fixture.vaNumber(),
                fixture.paymentCode(),
                "Payment " + fixture.paymentCode(),
                "{\"provider\":\"SIMULATOR\"}"
        );
    }

    private Fixture createFixture(Instant holdExpiry) {
        return inNewTransaction(() -> {
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            UUID ownerId = UUID.randomUUID();
            UUID driverId = UUID.randomUUID();
            UUID stationId = UUID.randomUUID();
            UUID chargePointId = UUID.randomUUID();
            UUID connectorId = UUID.randomUUID();
            UUID bookingId = UUID.randomUUID();
            UUID paymentId = UUID.randomUUID();
            String paymentCode = "CO" + suffix.toUpperCase();
            String vaNumber = "VA" + suffix.toUpperCase();
            String accountRef = "ACC-" + suffix.toUpperCase();
            Instant startAt = ACCEPTED_AT.plusSeconds(3600);
            Instant endAt = startAt.plusSeconds(3600);

            jdbcTemplate.update("""
                    INSERT INTO user_profile(id, keycloak_id, email)
                    VALUES (?, ?, ?), (?, ?, ?)
                    """,
                    ownerId, "owner-" + suffix, "owner-" + suffix + "@example.test",
                    driverId, "driver-" + suffix, "driver-" + suffix + "@example.test");
            jdbcTemplate.update("""
                    INSERT INTO stations(id, owner_id, name, address_line, contact_phone, status, operational_status)
                    VALUES (?, ?, ?, 'Test Address', '0123456789', 'ACTIVE', 'OPERATING')
                    """, stationId, ownerId, "Payment Race Station " + suffix);
            jdbcTemplate.update("""
                    INSERT INTO charge_points(id, station_id, charge_point_code,
                                              provisioning_status, operational_status)
                    VALUES (?, ?, ?, 'ACTIVE', 'AVAILABLE')
                    """, chargePointId, stationId, "CP-RACE-" + suffix);
            jdbcTemplate.update("""
                    INSERT INTO connectors(id, charge_point_id, connector_code, connector_type,
                                           power_kw, charger_type, runtime_status)
                    VALUES (?, ?, ?, 'CCS2', 60.0, 'DC', 'AVAILABLE')
                    """, connectorId, chargePointId, "CON-RACE-" + suffix);
            jdbcTemplate.update("""
                    INSERT INTO bookings(id, driver_id, connector_id, start_at, end_at, status,
                                         total_amount, station_name_snapshot, station_address_snapshot,
                                         charge_point_code_snapshot, connector_code_snapshot, expires_at)
                    VALUES (?, ?, ?, ?, ?, 'PENDING', ?, 'Race Station', 'Test Address',
                            'CP-RACE', 'CON-RACE', ?)
                    """,
                    bookingId,
                    driverId,
                    connectorId,
                    Timestamp.from(startAt),
                    Timestamp.from(endAt),
                    AMOUNT,
                    Timestamp.from(holdExpiry));
            jdbcTemplate.update("""
                    INSERT INTO payments(id, booking_id, amount, status, method, refund_amount,
                                         provider, receiving_account_ref, currency, version,
                                         needs_reconciliation, payment_code, provider_order_ref,
                                         va_number, provider_expires_at)
                    VALUES (?, ?, ?, 'PENDING', 'SIMULATOR', 0, 'SIMULATOR', ?, 'VND', 0,
                            false, ?, ?, ?, ?)
                    """,
                    paymentId,
                    bookingId,
                    AMOUNT,
                    accountRef,
                    paymentCode,
                    "ORDER-" + suffix,
                    vaNumber,
                    Timestamp.from(holdExpiry));

            return new Fixture(
                    connectorId,
                    bookingId,
                    paymentId,
                    paymentCode,
                    vaNumber,
                    accountRef,
                    startAt,
                    endAt
            );
        });
    }

    private <T> T inNewTransaction(Supplier<T> work) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> work.get());
    }

    private record Fixture(
            UUID connectorId,
            UUID bookingId,
            UUID paymentId,
            String paymentCode,
            String vaNumber,
            String accountRef,
            Instant startAt,
            Instant endAt
    ) {
    }
}
