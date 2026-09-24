package com.thang.chargeops.booking;

import com.thang.chargeops.booking.command.BookingCommandOperation;
import com.thang.chargeops.booking.command.BookingCommandRegistry;
import com.thang.chargeops.booking.command.BookingCommandRepository;
import com.thang.chargeops.booking.dto.request.CancelBookingRequest;
import com.thang.chargeops.booking.dto.response.BookingDetailResponse;
import com.thang.chargeops.booking.enums.CancellationRefundTier;
import com.thang.chargeops.booking.history.BookingStatusHistoryRecorder;
import com.thang.chargeops.booking.policy.BookingCancellationPolicy;
import com.thang.chargeops.booking.policy.model.CancellationRefundDecision;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.DriverBookingDetailAssembler;
import com.thang.chargeops.booking.service.impl.BookingCancellationServiceImpl;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.exception.errorcode.RefundErrorCode;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.refund.model.RefundStatus;
import com.thang.chargeops.refund.repository.RefundRepository;
import com.thang.chargeops.refund.service.RefundObligationService;
import com.thang.chargeops.refund.service.RefundObligationServiceImpl;
import com.thang.chargeops.station.repository.ConnectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.docker.compose.enabled=false"
})
@AutoConfigureTestDatabase(replace = NONE)
@Import({
        BookingCommandRegistry.class,
        BookingStatusHistoryRecorder.class,
        RefundObligationServiceImpl.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BookingCancellationPostgresTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("126000.00");
    private static final String POLICY_VERSION = "booking-v4.9";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                    .asCompatibleSubstituteFor("postgres")
    );

    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserProfileRepository userProfileRepository;
    @Autowired private ConnectorRepository connectorRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentTransactionRepository paymentTransactionRepository;
    @Autowired private BookingCommandRegistry bookingCommandRegistry;
    @Autowired private BookingCommandRepository bookingCommandRepository;
    @Autowired private BookingStatusHistoryRecorder bookingStatusHistoryRecorder;
    @Autowired private RefundObligationService refundObligationService;
    @Autowired private RefundRepository refundRepository;

    private BookingCancellationServiceImpl service;
    private UUID driverId;
    private UUID bookingId;

    @BeforeEach
    void setUp() {
        Fixture fixture = insertConfirmedPaidFixture();
        driverId = fixture.driverId();
        bookingId = fixture.bookingId();

        CurrentProfileProvider currentProfileProvider = mock(CurrentProfileProvider.class);
        UserProfile currentDriver = mock(UserProfile.class);
        when(currentDriver.getId()).thenReturn(driverId);
        when(currentProfileProvider.requireProfile()).thenReturn(currentDriver);

        BookingCancellationPolicy policy = mock(BookingCancellationPolicy.class);
        when(policy.calculateRefund(any(), eq(NOW), eq(false)))
                .thenReturn(new CancellationRefundDecision(
                        CancellationRefundTier.GRACE,
                        100,
                        AMOUNT,
                        BigDecimal.ZERO,
                        NOW.plusSeconds(600)
                ));

        DriverBookingDetailAssembler assembler = mock(DriverBookingDetailAssembler.class);
        when(assembler.assemble(any(), any(), eq(NOW))).thenAnswer(invocation -> {
            var booking = (com.thang.chargeops.booking.entity.Booking) invocation.getArgument(0);
            return BookingDetailResponse.builder()
                    .bookingId(booking.getId())
                    .status(booking.getStatus())
                    .persistedStatus(booking.getStatus())
                    .version(booking.getVersion())
                    .build();
        });

        service = new BookingCancellationServiceImpl(
                currentProfileProvider,
                userProfileRepository,
                connectorRepository,
                bookingRepository,
                paymentRepository,
                paymentTransactionRepository,
                bookingCommandRegistry,
                bookingStatusHistoryRecorder,
                policy,
                refundObligationService,
                assembler,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void successfulCancellationCommitsVersionHistoryCommandAndPendingRefund() {
        UUID requestKey = UUID.randomUUID();

        BookingDetailResponse response = cancelInTransaction(requestKey);

        var savedBooking = bookingRepository.findById(bookingId).orElseThrow();
        assertThat(response.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(response.version()).isEqualTo(1L);
        assertThat(savedBooking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(savedBooking.getVersion()).isEqualTo(1L);
        assertThat(bookingCommandRepository.findByActor_IdAndOperationAndRequestKey(
                driverId,
                BookingCommandOperation.CANCEL_BOOKING,
                requestKey
        )).isPresent();
        assertThat(countRows("booking_status_history", "booking_id", bookingId)).isEqualTo(1);
        assertThat(refundRepository.findByBookingIdOrderByCreatedAtAscIdAsc(bookingId))
                .singleElement()
                .satisfies(refund -> {
                    assertThat(refund.getAmount()).isEqualByComparingTo(AMOUNT);
                    assertThat(refund.getStatus()).isEqualTo(RefundStatus.PENDING);
                });
    }

    @Test
    void refundFailureRollsBackBookingCommandHistoryAndRefund() {
        jdbc.update("UPDATE payments SET environment='LEGACY' WHERE booking_id=?", bookingId);

        assertThatThrownBy(() -> cancelInTransaction(UUID.randomUUID()))
                .isInstanceOfSatisfying(
                        AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(RefundErrorCode.EXECUTION_CONFLICT)
                );

        var savedBooking = bookingRepository.findById(bookingId).orElseThrow();
        assertThat(savedBooking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(savedBooking.getVersion()).isZero();
        assertThat(countRows("booking_commands", "booking_id", bookingId)).isZero();
        assertThat(countRows("booking_status_history", "booking_id", bookingId)).isZero();
        assertThat(refundRepository.findByBookingIdOrderByCreatedAtAscIdAsc(bookingId)).isEmpty();
    }

    @Test
    void concurrentSameRequestKeyCommitsExactlyOneDecision() throws Exception {
        UUID requestKey = UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<BookingDetailResponse> first = pool.submit(
                    () -> cancelAfterBarrier(requestKey, ready, start)
            );
            Future<BookingDetailResponse> second = pool.submit(
                    () -> cancelAfterBarrier(requestKey, ready, start)
            );
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<BookingDetailResponse> responses = List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS)
            );
            assertThat(responses).allSatisfy(response -> {
                assertThat(response.status()).isEqualTo(BookingStatus.CANCELLED);
                assertThat(response.version()).isEqualTo(1L);
            });
        }

        assertThat(countRows("booking_commands", "booking_id", bookingId)).isEqualTo(1);
        assertThat(countRows("booking_status_history", "booking_id", bookingId)).isEqualTo(1);
        assertThat(refundRepository.findByBookingIdOrderByCreatedAtAscIdAsc(bookingId)).hasSize(1);
    }

    private BookingDetailResponse cancelAfterBarrier(
            UUID requestKey,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return cancelInTransaction(requestKey);
    }

    private BookingDetailResponse cancelInTransaction(UUID requestKey) {
        return new TransactionTemplate(transactionManager).execute(status -> service.cancelBooking(
                bookingId,
                requestKey,
                new CancelBookingRequest(0L, AMOUNT.longValueExact(), POLICY_VERSION)
        ));
    }

    private long countRows(String table, String column, UUID value) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Long.class,
                value
        );
    }

    private Fixture insertConfirmedPaidFixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        UUID fixtureDriverId = UUID.randomUUID();
        UUID stationId = UUID.randomUUID();
        UUID chargePointId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        UUID fixtureBookingId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        String paymentCode = "C" + suffix.toUpperCase();

        jdbc.update("""
                INSERT INTO user_profile(id, keycloak_id, email)
                VALUES (?, ?, ?)
                """, fixtureDriverId, "cancel-" + suffix, "cancel-" + suffix + "@example.test");
        jdbc.update("""
                INSERT INTO stations(id, owner_id, name, address_line, contact_phone, status)
                VALUES (?, ?, ?, 'Test Address', '0123456789', 'ACTIVE')
                """, stationId, fixtureDriverId, "Cancellation Station " + suffix);
        jdbc.update("""
                INSERT INTO charge_points(id, station_id, charge_point_code,
                                          provisioning_status, operational_status)
                VALUES (?, ?, ?, 'PROVISIONED', 'OPERATING')
                """, chargePointId, stationId, "CP-CAN-" + suffix);
        jdbc.update("""
                INSERT INTO connectors(id, charge_point_id, connector_code, connector_type,
                                       power_kw, charger_type, runtime_status)
                VALUES (?, ?, ?, 'CCS2', 60.0, 'DC', 'AVAILABLE')
                """, connectorId, chargePointId, "CON-CAN-" + suffix);
        jdbc.update("""
                INSERT INTO bookings(
                    id, driver_id, connector_id, booking_code, start_at, end_at, status,
                    total_amount, station_name_snapshot, station_address_snapshot,
                    charge_point_code_snapshot, connector_code_snapshot, expires_at,
                    policy_version, payment_confirmed_at, free_cancellation_deadline,
                    check_in_deadline, version
                )
                VALUES (?, ?, ?, ?, ?, ?, 'CONFIRMED', ?, 'Cancellation Station',
                        'Test Address', 'CP-CAN', 'CON-CAN', ?, ?, ?, ?, ?, 0)
                """,
                fixtureBookingId,
                fixtureDriverId,
                connectorId,
                "BK-CAN-" + suffix,
                Timestamp.from(NOW.plusSeconds(3600)),
                Timestamp.from(NOW.plusSeconds(7200)),
                AMOUNT,
                Timestamp.from(NOW.minusSeconds(600)),
                POLICY_VERSION,
                Timestamp.from(NOW.minusSeconds(300)),
                Timestamp.from(NOW.plusSeconds(600)),
                Timestamp.from(NOW.plusSeconds(5400))
        );
        jdbc.update("""
                INSERT INTO payments(
                    id, booking_id, amount, status, method, refund_amount, paid_at, provider,
                    receiving_account_ref, currency, version, needs_reconciliation,
                    payment_code, environment
                )
                VALUES (?, ?, ?, 'PAID', 'SIMULATOR', 0, ?, 'SIMULATOR', 'SIMULATOR',
                        'VND', 0, false, ?, 'SIMULATOR')
                """, paymentId, fixtureBookingId, AMOUNT, Timestamp.from(NOW.minusSeconds(300)), paymentCode);
        jdbc.update("""
                INSERT INTO payment_transactions(
                    payment_id, provider, receiving_account_ref, transaction_ref, amount,
                    currency, received_at, application_classification, payment_code,
                    va_number, version
                )
                VALUES (?, 'SIMULATOR', 'SIMULATOR', ?, ?, 'VND', ?, 'APPLIED', ?, ?, 0)
                """,
                paymentId,
                "TX-" + paymentCode,
                AMOUNT,
                Timestamp.from(NOW.minusSeconds(300)),
                paymentCode,
                "VA-" + paymentCode
        );

        return new Fixture(fixtureDriverId, fixtureBookingId);
    }

    private record Fixture(UUID driverId, UUID bookingId) {
    }
}
