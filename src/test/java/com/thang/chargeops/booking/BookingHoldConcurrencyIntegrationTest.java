package com.thang.chargeops.booking;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.history.BookingStatusActorType;
import com.thang.chargeops.booking.history.BookingStatusHistory;
import com.thang.chargeops.booking.history.BookingStatusHistoryRecorder;
import com.thang.chargeops.booking.history.BookingStatusHistoryRepository;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.BookingHoldCoordinator;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.response.ApiResult;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.GlobalHandlerError;
import com.thang.chargeops.exception.errorcode.BookingErrorCode;
import com.thang.chargeops.exception.errorcode.CommonErrorCode;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.station.repository.ConnectorRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.docker.compose.enabled=false"
})
@Import({
        BookingHoldCoordinator.class,
        BookingStatusHistoryRecorder.class,
        TestHoldTransactionalHarness.class,
        BookingHoldConcurrencyIntegrationTest.TestClockConfig.class
})
class BookingHoldConcurrencyIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        public Clock applicationClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private BookingHoldCoordinator coordinator;

    @Autowired
    private TestHoldTransactionalHarness harness;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private BookingStatusHistoryRepository historyRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private ConnectorRepository connectorRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID driverAId;
    private UUID driverBId;
    private UUID connector1Id;
    private UUID connector2Id;

    @BeforeEach
    void setUpFixtures() {
        driverAId = UUID.randomUUID();
        driverBId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID stationId = UUID.randomUUID();
        UUID chargePointId = UUID.randomUUID();
        connector1Id = UUID.randomUUID();
        connector2Id = UUID.randomUUID();

        String suffix = driverAId.toString().substring(0, 8);
        String provinceCode = "P" + suffix;
        String wardCode = "W" + suffix;

        insertUserProfile(driverAId, "driverA-" + suffix, "driverA-" + suffix + "@test.local");
        insertUserProfile(driverBId, "driverB-" + suffix, "driverB-" + suffix + "@test.local");
        insertUserProfile(ownerId, "owner-" + suffix, "owner-" + suffix + "@test.local");

        jdbcTemplate.update("INSERT INTO provinces(code, name, full_name) VALUES (?, ?, ?)",
                provinceCode, "Province " + suffix, "Province Full " + suffix);
        jdbcTemplate.update("INSERT INTO wards(code, name, full_name, province_code) VALUES (?, ?, ?, ?)",
                wardCode, "Ward " + suffix, "Ward Full " + suffix, provinceCode);

        jdbcTemplate.update("""
                INSERT INTO stations(id, station_code, owner_id, name, address_line, latitude, longitude,
                                    contact_phone, planned_charge_point_count, status, version, ward_code,
                                    created_at, updated_at)
                VALUES (?, ?, ?, 'Station Concurrency', 'Address', 10.0, 106.0, '0123456789', 1, 'ACTIVE', 0, ?, ?, ?)
                """, stationId, "ST-" + suffix, ownerId, wardCode, Timestamp.from(NOW), Timestamp.from(NOW));

        jdbcTemplate.update("""
                INSERT INTO charge_points(id, station_id, charge_point_code, max_power_kw,
                                         provisioning_status, operational_status, version, created_at, updated_at)
                VALUES (?, ?, ?, 60.0, 'ACTIVE', 'AVAILABLE', 0, ?, ?)
                """, chargePointId, stationId, "CP-" + suffix, Timestamp.from(NOW), Timestamp.from(NOW));

        jdbcTemplate.update("""
                INSERT INTO connectors(id, charge_point_id, connector_code, connector_type, power_kw,
                                       charger_type, runtime_status, version, created_at, updated_at)
                VALUES (?, ?, 'CON-01', 'CCS2', 60.0, 'DC', 'AVAILABLE', 0, ?, ?),
                       (?, ?, 'CON-02', 'CCS2', 60.0, 'DC', 'AVAILABLE', 0, ?, ?)
                """,
                connector1Id, chargePointId, Timestamp.from(NOW), Timestamp.from(NOW),
                connector2Id, chargePointId, Timestamp.from(NOW), Timestamp.from(NOW)
        );
    }

    private void insertUserProfile(UUID id, String keycloakId, String email) {
        jdbcTemplate.update("""
                INSERT INTO user_profile(id, keycloak_id, email, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """, id, keycloakId, email, Timestamp.from(NOW), Timestamp.from(NOW));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void lockDriver_outsideActiveTransaction_throwsIllegalTransactionStateException() {
        assertThatThrownBy(() -> coordinator.lockDriver(driverAId))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void raceTwoDrivers_sameConnectorAndInterval_onlyOneSucceedsOtherGetsSlotUnavailable() throws Exception {
        Instant startAt = Instant.parse("2026-09-16T10:00:00Z");
        Instant endAt = Instant.parse("2026-09-16T11:00:00Z");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger committed = new AtomicInteger();
        AtomicInteger slotUnavailableRejected = new AtomicInteger();
        AtomicReference<Throwable> otherError = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<Void> taskDriverA = () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                harness.attemptHold(driverAId, connector1Id, startAt, endAt, "BK-RACE-A");
                committed.incrementAndGet();
            } catch (AppException e) {
                if (e.getErrorCode() == BookingErrorCode.SLOT_UNAVAILABLE) {
                    slotUnavailableRejected.incrementAndGet();
                } else {
                    otherError.set(e);
                }
            } catch (Throwable t) {
                otherError.set(t);
            }
            return null;
        };

        Callable<Void> taskDriverB = () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                harness.attemptHold(driverBId, connector1Id, startAt, endAt, "BK-RACE-B");
                committed.incrementAndGet();
            } catch (AppException e) {
                if (e.getErrorCode() == BookingErrorCode.SLOT_UNAVAILABLE) {
                    slotUnavailableRejected.incrementAndGet();
                } else {
                    otherError.set(e);
                }
            } catch (Throwable t) {
                otherError.set(t);
            }
            return null;
        };

        try {
            Future<Void> f1 = executor.submit(taskDriverA);
            Future<Void> f2 = executor.submit(taskDriverB);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(otherError.get()).isNull();
        assertThat(committed).hasValue(1);
        assertThat(slotUnavailableRejected).hasValue(1);

        // Đối soát trực tiếp DB: đúng 1 booking được tạo
        Long bookingCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bookings WHERE connector_id = ? AND status = 'PENDING'",
                Long.class, connector1Id
        );
        assertThat(bookingCount).isEqualTo(1L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void raceOneDriver_twoDifferentConnectors_onlyOneSucceedsOtherGetsPendingLimitExceeded() throws Exception {
        Instant startAt = Instant.parse("2026-09-16T10:00:00Z");
        Instant endAt = Instant.parse("2026-09-16T11:00:00Z");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger committed = new AtomicInteger();
        AtomicInteger limitRejected = new AtomicInteger();
        AtomicReference<Throwable> otherError = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<Void> taskConnector1 = () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                harness.attemptHold(driverAId, connector1Id, startAt, endAt, "BK-ABUSE-1");
                committed.incrementAndGet();
            } catch (AppException e) {
                if (e.getErrorCode() == BookingErrorCode.PENDING_LIMIT_EXCEEDED) {
                    limitRejected.incrementAndGet();
                } else {
                    otherError.set(e);
                }
            } catch (Throwable t) {
                otherError.set(t);
            }
            return null;
        };

        Callable<Void> taskConnector2 = () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                harness.attemptHold(driverAId, connector2Id, startAt, endAt, "BK-ABUSE-2");
                committed.incrementAndGet();
            } catch (AppException e) {
                if (e.getErrorCode() == BookingErrorCode.PENDING_LIMIT_EXCEEDED) {
                    limitRejected.incrementAndGet();
                } else {
                    otherError.set(e);
                }
            } catch (Throwable t) {
                otherError.set(t);
            }
            return null;
        };

        try {
            Future<Void> f1 = executor.submit(taskConnector1);
            Future<Void> f2 = executor.submit(taskConnector2);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(otherError.get()).isNull();
        assertThat(committed).hasValue(1);
        assertThat(limitRejected).hasValue(1);

        // Đối soát DB: Driver A chỉ có duy nhất 1 booking PENDING
        Long driverBookingCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bookings WHERE driver_id = ? AND status = 'PENDING'",
                Long.class, driverAId
        );
        assertThat(driverBookingCount).isEqualTo(1L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void driverWithConfirmedPaidBooking_canBookAnotherConnectorWithoutPendingLimitError() {
        Instant startAt = Instant.parse("2026-09-16T10:00:00Z");
        Instant endAt = Instant.parse("2026-09-16T11:00:00Z");
        UUID paidBookingId = UUID.randomUUID();

        // 1. Driver A đã có sẵn 1 booking đã thanh toán (CONFIRMED) trên Connector 1
        requiresNewTransaction().executeWithoutResult(status -> {
            jdbcTemplate.update("""
                    INSERT INTO bookings(id, driver_id, connector_id, start_at, end_at, status,
                                         total_amount, station_name_snapshot, station_address_snapshot,
                                         charge_point_code_snapshot, connector_code_snapshot, expires_at,
                                         payment_confirmed_at, version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'CONFIRMED', 126000,
                            'Station Concurrency', 'Address', 'CP-01', 'CON-01', NULL,
                            ?, 0, ?, ?)
                    """,
                    paidBookingId, driverAId, connector1Id,
                    Timestamp.from(startAt), Timestamp.from(endAt),
                    Timestamp.from(NOW),
                    Timestamp.from(NOW),
                    Timestamp.from(NOW)
            );
        });

        // 2. Driver A tiếp tục đặt một booking mới trên Connector 2 cùng khoảng thời gian
        Booking newBooking = harness.attemptHold(driverAId, connector2Id, startAt, endAt, "BK-PAID-OVERLAP");

        // 3. Nghiệm thu tiêu chí BKG-022:
        // - Booking mới tạo thành công ở trạng thái PENDING
        assertThat(newBooking).isNotNull();
        assertThat(newBooking.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(newBooking.getConnector().getId()).isEqualTo(connector2Id);

        // - Driver A hiện có 2 booking: 1 CONFIRMED và 1 PENDING (không cấm booking đã trả bị chồng giờ ở cổng khác)
        Long totalBookings = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bookings WHERE driver_id = ?",
                Long.class, driverAId
        );
        assertThat(totalBookings).isEqualTo(2L);

        Long pendingBookings = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bookings WHERE driver_id = ? AND status = 'PENDING'",
                Long.class, driverAId
        );
        assertThat(pendingBookings).isEqualTo(1L);

        Long confirmedBookings = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bookings WHERE driver_id = ? AND status = 'CONFIRMED'",
                Long.class, driverAId
        );
        assertThat(confirmedBookings).isEqualTo(1L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void twoConsecutiveIntervals_touchingEndpoints_bothSucceedWithoutOverlapConflict() {
        Instant slot1Start = Instant.parse("2026-09-16T10:00:00Z");
        Instant slot1End = Instant.parse("2026-09-16T11:00:00Z");
        Instant slot2Start = Instant.parse("2026-09-16T11:00:00Z");
        Instant slot2End = Instant.parse("2026-09-16T12:00:00Z");

        Booking b1 = harness.attemptHold(driverAId, connector1Id, slot1Start, slot1End, "BK-TOUCH-1");
        Booking b2 = harness.attemptHold(driverBId, connector1Id, slot2Start, slot2End, "BK-TOUCH-2");

        assertThat(b1.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(b2.getStatus()).isEqualTo(BookingStatus.PENDING);

        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bookings WHERE connector_id = ? AND status = 'PENDING'",
                Long.class, connector1Id
        );
        assertThat(count).isEqualTo(2L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void lazyExpiry_overduePendingBooking_transitionsToExpiredAndReleasesSlot() {
        Instant startAt = Instant.parse("2026-09-16T10:00:00Z");
        Instant endAt = Instant.parse("2026-09-16T11:00:00Z");
        UUID expiredBookingId = UUID.randomUUID();

        // 1. Tạo 1 PENDING booking đã quá hạn (expiresAt = NOW - 10s)
        requiresNewTransaction().executeWithoutResult(status -> {
            jdbcTemplate.update("""
                    INSERT INTO bookings(id, driver_id, connector_id, start_at, end_at, status,
                                         total_amount, station_name_snapshot, station_address_snapshot,
                                         charge_point_code_snapshot, connector_code_snapshot, expires_at,
                                         version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'PENDING', 126000,
                            'Station', 'Address', 'CP-01', 'CON-01', ?, 0, ?, ?)
                    """,
                    expiredBookingId, driverAId, connector1Id,
                    Timestamp.from(startAt), Timestamp.from(endAt),
                    Timestamp.from(NOW.minusSeconds(10)),
                    Timestamp.from(NOW.minusSeconds(610)),
                    Timestamp.from(NOW.minusSeconds(610))
            );
        });

        // 2. Driver B đặt đúng khoảng giờ đó trên connector1
        Booking newBooking = harness.attemptHold(driverBId, connector1Id, startAt, endAt, "BK-NEW-HOLD");
        assertThat(newBooking.getStatus()).isEqualTo(BookingStatus.PENDING);

        // 3. Đối soát DB đủ 3 điều kiện:
        // - Booking cũ chuyển EXPIRED
        String oldStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, expiredBookingId
        );
        assertThat(oldStatus).isEqualTo("EXPIRED");

        // - Booking mới là PENDING
        String newStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, newBooking.getId()
        );
        assertThat(newStatus).isEqualTo("PENDING");

        // - Lịch sử ghi nhận chuyển đổi SYSTEM (from PENDING to EXPIRED)
        List<BookingStatusHistory> histories = historyRepository.findByBooking_IdOrderByOccurredAtAscIdAsc(expiredBookingId);
        assertThat(histories).hasSize(1);
        BookingStatusHistory history = histories.get(0);
        assertThat(history.getFromStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(history.getToStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(history.getActorType()).isEqualTo(BookingStatusActorType.SYSTEM);
        assertThat(history.getReason()).isEqualTo("HOLD_EXPIRED");
    }

    @Test
    void globalHandlerError_translatesExclusionConstraintViolationToSlotUnavailable() {
        GlobalHandlerError handler = new GlobalHandlerError();
        SQLException sqlException = new SQLException("ERROR: conflicting key value violates exclusion constraint \"ex_bookings_no_overlap\"");
        ConstraintViolationException cve = new ConstraintViolationException("exclusion error", sqlException, "ex_bookings_no_overlap");
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Data integrity", cve);

        ResponseEntity<ApiResult<?>> response = handler.handleDataIntegrity(dive);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError().getCode()).isEqualTo("BKG_SLOT_UNAVAILABLE");
    }

    @Test
    void globalHandlerError_otherDataIntegrityViolation_returns409DataIntegrityError() {
        GlobalHandlerError handler = new GlobalHandlerError();
        SQLException sqlException = new SQLException("ERROR: null value in column \"title\" violates not-null constraint");
        ConstraintViolationException cve = new ConstraintViolationException("not null error", sqlException, "nn_title");
        DataIntegrityViolationException dive = new DataIntegrityViolationException("Data integrity", cve);

        ResponseEntity<ApiResult<?>> response = handler.handleDataIntegrity(dive);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError().getCode()).isEqualTo(CommonErrorCode.DATA_INTEGRITY_ERROR.getCode());
    }

    private TransactionTemplate requiresNewTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }
}
