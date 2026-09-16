package com.thang.chargeops.booking;

import com.thang.chargeops.booking.command.*;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.history.*;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.CommandErrorCode;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.docker.compose.enabled=false"
})
@Import({BookingCommandRegistry.class, BookingStatusHistoryRecorder.class})
class BookingCommandHistoryJpaTest {

    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");

    @Autowired
    private BookingCommandRegistry commandRegistry;

    @Autowired
    private BookingCommandRepository commandRepository;

    @Autowired
    private BookingStatusHistoryRecorder historyRecorder;

    @Autowired
    private BookingStatusHistoryRepository historyRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID actorId;
    private UUID bookingId;

    @BeforeEach
    void setUpFixture() {
        actorId = UUID.randomUUID();
        bookingId = UUID.randomUUID();
        UUID stationId = UUID.randomUUID();
        UUID chargePointId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        String suffix = actorId.toString();
        String shortSuffix = suffix.substring(0, 8);
        String provinceCode = "P" + shortSuffix;
        String wardCode = "W" + shortSuffix;

        jdbcTemplate.update(
                """
                INSERT INTO user_profile(id, keycloak_id, email, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                actorId,
                "command-" + suffix,
                "command-" + suffix + "@example.test",
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        );
        jdbcTemplate.update(
                "INSERT INTO provinces(code, name, full_name) VALUES (?, ?, ?)",
                provinceCode,
                "Province " + shortSuffix,
                "Test Province " + shortSuffix
        );
        jdbcTemplate.update(
                "INSERT INTO wards(code, name, full_name, province_code) VALUES (?, ?, ?, ?)",
                wardCode,
                "Ward " + shortSuffix,
                "Test Ward " + shortSuffix,
                provinceCode
        );
        jdbcTemplate.update("""
                INSERT INTO stations(
                    id, station_code, owner_id, name, address_line, latitude, longitude,
                    contact_phone, planned_charge_point_count, status, version, ward_code,
                    created_at, updated_at
                )
                VALUES (?, ?, ?, ?, 'Test Address', 10.0, 106.0,
                        '0123456789', 1, 'ACTIVE', 0, ?, ?, ?)
                """,
                stationId,
                "ST-" + shortSuffix,
                actorId,
                "Command Station " + shortSuffix,
                wardCode,
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        );
        jdbcTemplate.update("""
                INSERT INTO charge_points(
                    id, station_id, charge_point_code, max_power_kw,
                    provisioning_status, operational_status, version, created_at, updated_at
                )
                VALUES (?, ?, ?, 60.0, 'ACTIVE', 'AVAILABLE', 0, ?, ?)
                """,
                chargePointId,
                stationId,
                "CP-" + suffix,
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        );
        jdbcTemplate.update("""
                INSERT INTO connectors(
                    id, charge_point_id, connector_code, connector_type,
                    power_kw, charger_type, runtime_status, version, created_at, updated_at
                )
                VALUES (?, ?, ?, 'CCS2', 60.0, 'DC', 'AVAILABLE', 0, ?, ?)
                """,
                connectorId,
                chargePointId,
                "CON-" + suffix,
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        );
        jdbcTemplate.update("""
                INSERT INTO bookings(id, driver_id, connector_id, start_at, end_at, status,
                                     total_amount, station_name_snapshot, station_address_snapshot,
                                     charge_point_code_snapshot, connector_code_snapshot, expires_at,
                                     version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING', 126000,
                        'Command Station', 'Test Address', 'CP-01', 'CON-01', ?, 0, ?, ?)
                """,
                bookingId,
                actorId,
                connectorId,
                Timestamp.from(Instant.parse("2026-09-16T10:00:00Z")),
                Timestamp.from(Instant.parse("2026-09-16T11:00:00Z")),
                Timestamp.from(Instant.parse("2026-09-15T10:10:00Z")),
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        );
    }

    @Test
    void recordsSuccessfulCommandAndReplaysOnlyInsideTheActorScope() {
        UserProfile actor = userProfileRepository.findById(actorId).orElseThrow();
        Booking booking = bookingRepository.findById(bookingId).orElseThrow();
        UUID requestKey = UUID.randomUUID();
        String payloadHash = BookingCommandPayloadHasher.sha256("create-v1");

        commandRegistry.recordSuccess(
                actor, BookingCommandOperation.CREATE_BOOKING, requestKey,
                payloadHash, booking, NOW
        );

        assertThat(commandRegistry.findReplay(
                actorId, BookingCommandOperation.CREATE_BOOKING, requestKey, payloadHash
        )).contains(bookingId);

        UUID otherActorId = UUID.randomUUID();
        assertThat(commandRegistry.findReplay(
                otherActorId, BookingCommandOperation.CREATE_BOOKING, requestKey, payloadHash
        )).isEmpty();
    }

    @Test
    void rejectsAReusedKeyWhenThePayloadChanged() {
        UserProfile actor = userProfileRepository.findById(actorId).orElseThrow();
        Booking booking = bookingRepository.findById(bookingId).orElseThrow();
        UUID requestKey = UUID.randomUUID();
        commandRegistry.recordSuccess(
                actor, BookingCommandOperation.CREATE_BOOKING, requestKey,
                BookingCommandPayloadHasher.sha256("create-v1"), booking, NOW
        );

        assertThatThrownBy(() -> commandRegistry.findReplay(
                actorId,
                BookingCommandOperation.CREATE_BOOKING,
                requestKey,
                BookingCommandPayloadHasher.sha256("create-v2")
        )).isInstanceOf(AppException.class)
                .satisfies(error -> assertThat(((AppException) error).getErrorCode())
                        .isEqualTo(CommandErrorCode.KEY_REUSED));
    }

    @Test
    void recordsCreationAndSystemExpirationWithoutDuplicatingOnReplay() {
        UserProfile actor = userProfileRepository.findById(actorId).orElseThrow();
        Booking booking = bookingRepository.findById(bookingId).orElseThrow();
        UUID requestKey = UUID.randomUUID();
        String payloadHash = BookingCommandPayloadHasher.sha256("create-v1");
        BookingCommand command = commandRegistry.recordSuccess(
                actor, BookingCommandOperation.CREATE_BOOKING, requestKey,
                payloadHash, booking, NOW
        );
        historyRecorder.recordCreation(
                command, BookingStatusActorType.DRIVER, NOW
        );

        assertThat(commandRegistry.findReplay(
                actorId, BookingCommandOperation.CREATE_BOOKING, requestKey, payloadHash
        )).contains(bookingId);
        assertThat(historyRepository.countByBooking_IdAndCommand_Id(bookingId, command.getId()))
                .isEqualTo(1);

        historyRecorder.recordSystemTransition(
                booking,
                BookingStatusReason.HOLD_EXPIRED,
                NOW.plusSeconds(600),
                Booking::expire
        );
        entityManager.flush();

        var timeline = historyRepository.findByBooking_IdOrderByOccurredAtAscIdAsc(bookingId);
        assertThat(timeline).hasSize(2);
        assertThat(timeline.get(1).getFromStatus().name()).isEqualTo("PENDING");
        assertThat(timeline.get(1).getToStatus().name()).isEqualTo("EXPIRED");
        assertThat(timeline.get(1).getActorType()).isEqualTo(BookingStatusActorType.SYSTEM);
        assertThat(timeline.get(1).getActor()).isNull();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void commandAndHistoryRollbackTogetherWithTheBookingMutation() {
        TransactionTemplate transaction = requiresNewTransaction();

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            UserProfile actor = userProfileRepository.findById(actorId).orElseThrow();
            Booking booking = bookingRepository.findById(bookingId).orElseThrow();
            BookingCommand command = commandRegistry.recordSuccess(
                    actor, BookingCommandOperation.CANCEL_BOOKING, UUID.randomUUID(),
                    BookingCommandPayloadHasher.sha256("cancel-v1"), booking, NOW
            );
            historyRecorder.recordUserTransition(
                    command,
                    BookingStatusActorType.DRIVER,
                    BookingStatusReason.DRIVER_CANCELLED,
                    NOW,
                    value -> value.cancel(BookingStatusReason.DRIVER_CANCELLED.name(), NOW)
            );
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("force rollback");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, bookingId
        )).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM booking_commands WHERE booking_id = ?", Long.class, bookingId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM booking_status_history WHERE booking_id = ?", Long.class, bookingId
        )).isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void uniqueConstraintAllowsOnlyOneConcurrentReceipt() throws Exception {
        UUID requestKey = UUID.randomUUID();
        String payloadHash = BookingCommandPayloadHasher.sha256("concurrent-create");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger committed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Callable<Void> attempt = () -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            try {
                requiresNewTransaction().executeWithoutResult(status -> {
                    UserProfile actor = userProfileRepository.findById(actorId).orElseThrow();
                    Booking booking = bookingRepository.findById(bookingId).orElseThrow();
                    commandRegistry.recordSuccess(
                            actor, BookingCommandOperation.CREATE_BOOKING, requestKey,
                            payloadHash, booking, NOW
                    );
                });
                committed.incrementAndGet();
            } catch (DataIntegrityViolationException exception) {
                rejected.incrementAndGet();
            }
            return null;
        };

        try {
            Future<Void> first = executor.submit(attempt);
            Future<Void> second = executor.submit(attempt);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(committed).hasValue(1);
        assertThat(rejected).hasValue(1);
        assertThat(commandRepository.findByActor_IdAndOperationAndRequestKey(
                actorId, BookingCommandOperation.CREATE_BOOKING, requestKey
        )).isPresent();
    }

    private TransactionTemplate requiresNewTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }
}
