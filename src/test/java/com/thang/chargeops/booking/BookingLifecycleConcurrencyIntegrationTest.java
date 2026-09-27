package com.thang.chargeops.booking;

import com.thang.chargeops.booking.history.BookingStatusHistoryRecorder;
import com.thang.chargeops.booking.history.BookingStatusHistoryRepository;
import com.thang.chargeops.booking.projection.BookingLifecycleCandidateProjection;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.BookingAutomaticCompletionService;
import com.thang.chargeops.booking.service.BookingNoShowService;
import com.thang.chargeops.booking.service.BookingSessionCompletionCoordinator;
import com.thang.chargeops.booking.service.impl.BookingAutomaticCompletionServiceImpl;
import com.thang.chargeops.booking.service.impl.BookingNoShowServiceImpl;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.station.repository.ConnectorRepository;
import com.thang.chargeops.station.service.impl.EquipmentStatusHistoryServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
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
@Import({
        BookingStatusHistoryRecorder.class,
        BookingNoShowServiceImpl.class,
        BookingSessionCompletionCoordinator.class,
        BookingAutomaticCompletionServiceImpl.class,
        EquipmentStatusHistoryServiceImpl.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BookingLifecycleConcurrencyIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-27T04:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("120000.00");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                    .asCompatibleSubstituteFor("postgres")
    );

    @Autowired private BookingNoShowService noShowService;
    @Autowired private BookingAutomaticCompletionService completionService;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private ConnectorRepository connectorRepository;
    @Autowired private BookingStatusHistoryRepository historyRepository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void twoWorkersMarkDueNoShowExactlyOnceAndKeepPaymentPaid() throws Exception {
        Fixture fixture = createFixture(BookingStatus.CONFIRMED, "AVAILABLE");

        List<BookingLifecycleCandidateProjection> candidates =
                bookingRepository.findDueNoShowCandidates(NOW, PageRequest.of(0, 10));
        assertThat(candidates).extracting(BookingLifecycleCandidateProjection::getBookingId)
                .contains(fixture.bookingId());

        List<Boolean> results = runTwoWorkers(() -> noShowService.markNoShowIfDue(
                fixture.bookingId(),
                fixture.connectorId(),
                NOW
        ));

        assertThat(results).containsExactlyInAnyOrder(true, false);
        var booking = bookingRepository.findById(fixture.bookingId()).orElseThrow();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(booking.getCancellationReason()).isEqualTo("NO_SHOW");
        assertThat(paymentRepository.findById(fixture.paymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PAID);
        assertThat(historyRepository.findByBooking_IdOrderByOccurredAtAscIdAsc(fixture.bookingId()))
                .singleElement()
                .satisfies(history -> {
                    assertThat(history.getReason()).isEqualTo("NO_SHOW");
                    assertThat(history.getActorType().name()).isEqualTo("SYSTEM");
                });
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM refunds WHERE booking_id = ?",
                Long.class,
                fixture.bookingId()
        )).isZero();
    }

    @Test
    void twoWorkersCompleteDueSessionExactlyOnceAndReleaseConnector() throws Exception {
        Fixture fixture = createFixture(BookingStatus.CHARGING, "IN_USE");

        List<BookingLifecycleCandidateProjection> candidates =
                bookingRepository.findDueSessionCompletionCandidates(NOW, PageRequest.of(0, 10));
        assertThat(candidates).extracting(BookingLifecycleCandidateProjection::getBookingId)
                .contains(fixture.bookingId());

        List<Boolean> results = runTwoWorkers(() -> completionService.completeIfDue(
                fixture.bookingId(),
                fixture.connectorId(),
                NOW
        ));

        assertThat(results).containsExactlyInAnyOrder(true, false);
        var booking = bookingRepository.findById(fixture.bookingId()).orElseThrow();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(booking.getCompletedAt()).isEqualTo(NOW);
        assertThat(connectorRepository.findById(fixture.connectorId()).orElseThrow().getRuntimeStatus().name())
                .isEqualTo("AVAILABLE");
        assertThat(historyRepository.findByBooking_IdOrderByOccurredAtAscIdAsc(fixture.bookingId()))
                .singleElement()
                .satisfies(history -> {
                    assertThat(history.getReason()).isEqualTo("SESSION_COMPLETED");
                    assertThat(history.getActorType().name()).isEqualTo("SYSTEM");
                });
    }

    private List<Boolean> runTwoWorkers(WorkerCall call) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> invokeAfterBarrier(call, ready, start));
            Future<Boolean> second = executor.submit(() -> invokeAfterBarrier(call, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean invokeAfterBarrier(
            WorkerCall call,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            return false;
        }
        return call.invoke();
    }

    private Fixture createFixture(BookingStatus status, String runtimeStatus) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String provinceCode = suffix.substring(0, 4).toUpperCase();
        String wardCode = suffix.substring(4, 10).toUpperCase();
        UUID ownerId = UUID.randomUUID();
        UUID driverId = UUID.randomUUID();
        UUID stationId = UUID.randomUUID();
        UUID chargePointId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Instant startAt = NOW.minusSeconds(3600);
        Instant endAt = status == BookingStatus.CONFIRMED
                ? NOW.plusSeconds(15 * 60)
                : NOW;

        jdbc.update("INSERT INTO provinces(code, name, full_name) VALUES (?, ?, ?)",
                provinceCode, "Province " + suffix, "Province " + suffix);
        jdbc.update("INSERT INTO wards(code, name, full_name, province_code) VALUES (?, ?, ?, ?)",
                wardCode, "Ward " + suffix, "Ward " + suffix, provinceCode);
        jdbc.update("""
                INSERT INTO user_profile(id, keycloak_id, email)
                VALUES (?, ?, ?), (?, ?, ?)
                """,
                ownerId, "owner-" + suffix, "owner-" + suffix + "@example.test",
                driverId, "driver-" + suffix, "driver-" + suffix + "@example.test");
        jdbc.update("""
                INSERT INTO stations(
                    id, station_code, owner_id, name, address_line, ward_code,
                    latitude, longitude, contact_phone, planned_charge_point_count,
                    status, operational_status, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'Test Address', ?, 10.0, 106.0,
                          '0900000000', 1, 'ACTIVE', 'OPERATING', 0, ?, ?)
                """,
                stationId, "ST-" + suffix, ownerId, "Lifecycle Station " + suffix,
                wardCode, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO charge_points(
                    id, station_id, charge_point_code, max_power_kw,
                    provisioning_status, operational_status, version, created_at, updated_at
                ) VALUES (?, ?, ?, 60.0, 'ACTIVE', 'AVAILABLE', 0, ?, ?)
                """,
                chargePointId, stationId, "CP-" + suffix,
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO connectors(
                    id, charge_point_id, connector_code, connector_type, power_kw,
                    charger_type, runtime_status, version, created_at, updated_at
                ) VALUES (?, ?, ?, 'CCS2', 60.0, 'DC', ?, 0, ?, ?)
                """,
                connectorId, chargePointId, "CON-" + suffix, runtimeStatus,
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO bookings(
                    id, driver_id, connector_id, booking_code, start_at, end_at, status,
                    total_amount, station_name_snapshot, station_address_snapshot,
                    charge_point_code_snapshot, connector_code_snapshot, expires_at,
                    payment_confirmed_at, free_cancellation_deadline, check_in_deadline,
                    checked_in_at, charging_started_at, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'Lifecycle Station', 'Test Address',
                          'CP-LIFE', 'CON-LIFE', ?, ?, ?, ?, ?, ?, 0, ?, ?)
                """,
                bookingId, driverId, connectorId, "BK-" + suffix.toUpperCase(),
                Timestamp.from(startAt), Timestamp.from(endAt), status.name(), AMOUNT,
                Timestamp.from(startAt.minusSeconds(600)),
                Timestamp.from(startAt.minusSeconds(600)),
                Timestamp.from(startAt.minusSeconds(300)),
                Timestamp.from(NOW),
                status == BookingStatus.CHARGING ? Timestamp.from(startAt) : null,
                status == BookingStatus.CHARGING ? Timestamp.from(startAt.plusSeconds(60)) : null,
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO payments(
                    id, booking_id, amount, status, method, refund_amount, paid_at,
                    provider, receiving_account_ref, currency, version,
                    needs_reconciliation, payment_code, provider_order_ref,
                    va_number, provider_expires_at
                ) VALUES (?, ?, ?, 'PAID', 'SIMULATOR', 0, ?, 'SIMULATOR', ?, 'VND', 0,
                          false, ?, ?, ?, ?)
                """,
                paymentId, bookingId, AMOUNT, Timestamp.from(startAt.minusSeconds(300)),
                "ACC-" + suffix, "CO" + suffix.toUpperCase(), "ORDER-" + suffix,
                "VA" + suffix.toUpperCase(), Timestamp.from(endAt));

        return new Fixture(connectorId, bookingId, paymentId);
    }

    @FunctionalInterface
    private interface WorkerCall {
        boolean invoke();
    }

    private record Fixture(UUID connectorId, UUID bookingId, UUID paymentId) {
    }
}
