package com.thang.chargeops.booking;

import com.thang.chargeops.booking.history.BookingStatusHistoryRecorder;
import com.thang.chargeops.booking.history.BookingStatusHistoryRepository;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.BookingExpirationService;
import com.thang.chargeops.booking.service.impl.BookingExpirationServiceImpl;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.payment.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.docker.compose.enabled=false"
})
@AutoConfigureTestDatabase(replace = NONE)
@Import({BookingStatusHistoryRecorder.class, BookingExpirationServiceImpl.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BookingExpirationConcurrencyIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-21T03:05:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("120000.00");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                    .asCompatibleSubstituteFor("postgres")
    );

    @Autowired
    private BookingExpirationService expirationService;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private BookingStatusHistoryRepository historyRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void twoWorkersExpireAnOverdueHoldExactlyOnce() throws Exception {
        Fixture fixture = createFixture(PaymentStatus.PENDING);

        List<Boolean> results = runTwoWorkers(fixture);

        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(bookingRepository.findById(fixture.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.EXPIRED);
        assertThat(paymentRepository.findById(fixture.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED);
        assertThat(historyRepository.findByBooking_IdOrderByOccurredAtAscIdAsc(fixture.bookingId()))
                .hasSize(1);
    }

    @Test
    void twoWorkersNeverExpireAnOverdueBookingWhosePaymentIsAlreadyPaid() throws Exception {
        Fixture fixture = createFixture(PaymentStatus.PAID);

        List<Boolean> results = runTwoWorkers(fixture);

        assertThat(results).containsOnly(false);
        assertThat(bookingRepository.findById(fixture.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.PENDING);
        assertThat(paymentRepository.findById(fixture.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PAID);
        assertThat(historyRepository.findByBooking_IdOrderByOccurredAtAscIdAsc(fixture.bookingId()))
                .isEmpty();
    }

    private List<Boolean> runTwoWorkers(Fixture fixture) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> expireAfterBarrier(fixture, ready, start));
            Future<Boolean> second = executor.submit(() -> expireAfterBarrier(fixture, ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean expireAfterBarrier(
            Fixture fixture,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            return false;
        }
        return expirationService.expireIfDue(fixture.bookingId(), fixture.connectorId(), NOW);
    }

    private Fixture createFixture(PaymentStatus paymentStatus) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        UUID ownerId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();
        UUID stationId = UUID.randomUUID();
        UUID chargePointId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Instant startAt = NOW.plusSeconds(3600);
        Instant endAt = startAt.plusSeconds(3600);
        Instant holdExpiry = NOW.minusSeconds(1);

        jdbcTemplate.update("""
                INSERT INTO user_profile(id, keycloak_id, email)
                VALUES (?, ?, ?), (?, ?, ?)
                """,
                ownerId, "owner-" + suffix, "owner-" + suffix + "@example.test",
                driverId, "driver-" + suffix, "driver-" + suffix + "@example.test");
        jdbcTemplate.update("""
                INSERT INTO stations(id, owner_id, name, address_line, contact_phone, status)
                VALUES (?, ?, ?, 'Test Address', '0123456789', 'ACTIVE')
                """, stationId, ownerId, "Expiration Station " + suffix);
        jdbcTemplate.update("""
                INSERT INTO charge_points(id, station_id, charge_point_code,
                                          provisioning_status, operational_status)
                VALUES (?, ?, ?, 'PROVISIONED', 'OPERATING')
                """, chargePointId, stationId, "CP-EXP-" + suffix);
        jdbcTemplate.update("""
                INSERT INTO connectors(id, charge_point_id, connector_code, connector_type,
                                       power_kw, charger_type, runtime_status)
                VALUES (?, ?, ?, 'CCS2', 60.0, 'DC', 'AVAILABLE')
                """, connectorId, chargePointId, "CON-EXP-" + suffix);
        jdbcTemplate.update("""
                INSERT INTO bookings(id, driver_id, connector_id, start_at, end_at, status,
                                     total_amount, station_name_snapshot, station_address_snapshot,
                                     charge_point_code_snapshot, connector_code_snapshot, expires_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?, 'Expiration Station', 'Test Address',
                        'CP-EXP', 'CON-EXP', ?)
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
                                     paid_at, provider, receiving_account_ref, currency, version,
                                     needs_reconciliation, payment_code, provider_order_ref,
                                     va_number, provider_expires_at)
                VALUES (?, ?, ?, ?, 'SIMULATOR', 0, ?, 'SIMULATOR', ?, 'VND', 0,
                        false, ?, ?, ?, ?)
                """,
                paymentId,
                bookingId,
                AMOUNT,
                paymentStatus.name(),
                paymentStatus == PaymentStatus.PAID ? Timestamp.from(NOW.minusSeconds(30)) : null,
                "ACC-" + suffix.toUpperCase(),
                "CO" + suffix.toUpperCase(),
                "ORDER-" + suffix,
                "VA" + suffix.toUpperCase(),
                Timestamp.from(holdExpiry));

        return new Fixture(connectorId, bookingId, paymentId);
    }

    private record Fixture(UUID connectorId, UUID bookingId, UUID paymentId) {
    }
}
