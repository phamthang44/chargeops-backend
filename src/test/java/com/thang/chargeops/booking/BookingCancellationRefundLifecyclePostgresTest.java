package com.thang.chargeops.booking;

import com.thang.chargeops.common.enums.PaymentApplicationClassification;
import com.thang.chargeops.common.enums.PaymentStatus;
import com.thang.chargeops.booking.command.BookingCommandInFlightLock;
import com.thang.chargeops.payment.model.NormalizedReceipt;
import com.thang.chargeops.payment.model.PaymentReceiptResult;
import com.thang.chargeops.payment.repository.PaymentRepository;
import com.thang.chargeops.payment.repository.PaymentTransactionRepository;
import com.thang.chargeops.payment.service.PaymentConfirmationService;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.refund.model.RefundAttemptStatus;
import com.thang.chargeops.refund.model.RefundStatus;
import com.thang.chargeops.refund.repository.RefundAttemptRepository;
import com.thang.chargeops.refund.repository.RefundRepository;
import com.thang.chargeops.refund.service.AutomaticRefundExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        webEnvironment = WebEnvironment.MOCK,
        properties = {
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.flyway.enabled=true",
                "spring.docker.compose.enabled=false",
                "spring.task.scheduling.enabled=false",
                "app.refund.auto-execution.enabled=false"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("BKG-038: cancel/refund M2 REST and PostgreSQL verification")
class BookingCancellationRefundLifecyclePostgresTest {

    private static final Instant NOW = Instant.parse("2026-09-26T03:00:00Z");
    private static final Instant START_AT = Instant.parse("2026-09-26T05:00:00Z");
    private static final String POLICY_VERSION = "booking-v4.9";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                    .asCompatibleSubstituteFor("postgres")
    );

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserProfileRepository userProfileRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentTransactionRepository paymentTransactionRepository;
    @Autowired private PaymentConfirmationService paymentConfirmationService;
    @Autowired private RefundRepository refundRepository;
    @Autowired private RefundAttemptRepository refundAttemptRepository;
    @Autowired private AutomaticRefundExecutionService automaticRefundExecutionService;

    @MockitoBean private CurrentProfileProvider currentProfileProvider;
    @MockitoBean private Clock applicationClock;
    @MockitoBean private BookingCommandInFlightLock bookingCommandInFlightLock;

    private final AtomicReference<UUID> currentActorId = new AtomicReference<>();
    private UUID driverId;
    private UUID adminId;
    private UUID connectorId;

    @BeforeEach
    void setUp() {
        when(applicationClock.instant()).thenReturn(NOW);
        when(applicationClock.getZone()).thenReturn(ZoneOffset.UTC);
        when(applicationClock.withZone(org.mockito.ArgumentMatchers.any()))
                .thenReturn(applicationClock);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get())
                .when(bookingCommandInFlightLock)
                .executeWithLock(any(), any(), any(), any());
        when(currentProfileProvider.requireProfile()).thenAnswer(invocation ->
                userProfileRepository.findById(currentActorId.get()).orElseThrow());

        Fixture fixture = insertBookableStationFixture();
        driverId = fixture.driverId();
        adminId = fixture.adminId();
        connectorId = fixture.connectorId();
        currentActorId.set(driverId);
    }

    @Test
    @DisplayName("REST create -> Simulator paid -> grace cancel -> automatic first refund -> refunded")
    void fullRestLifecycleIsReplaySafeAndNeverExceedsCollectedAmount() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT);
        UUID cancelKey = UUID.randomUUID();

        JsonNode cancelled = cancel(paid, cancelKey, paid.amount(), 200);
        UUID refundId = UUID.fromString(cancelled.at("/data/refunds/0/refundId").asText());

        assertThat(cancelled.at("/data/status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.at("/data/refunds/0/status").asText()).isEqualTo("PENDING");
        assertThat(refundRepository.findById(refundId)).get()
                .extracting(refund -> refund.getStatus())
                .isEqualTo(RefundStatus.PENDING);

        JsonNode replay = cancel(paid, cancelKey, paid.amount(), 200);
        assertThat(replay.at("/data/refunds/0/refundId").asText()).isEqualTo(refundId.toString());
        assertThat(refundRepository.findByBookingIdOrderByCreatedAtAscIdAsc(paid.bookingId()))
                .hasSize(1);

        assertThat(automaticRefundExecutionService.processFirstAttempt(refundId)).isTrue();
        assertThat(refundRepository.findById(refundId).orElseThrow().getStatus())
                .isEqualTo(RefundStatus.SUCCEEDED);
        var successfulAttempt = refundAttemptRepository.findByRefundIdOrderBySequenceNoAsc(refundId)
                .stream().filter(attempt -> attempt.getStatus() == RefundAttemptStatus.SUCCEEDED)
                .toList();
        assertThat(successfulAttempt).hasSize(1);
        assertThat(automaticRefundExecutionService.processFirstAttempt(refundId)).isFalse();
        assertThat(refundAttemptRepository.findByRefundIdOrderBySequenceNoAsc(refundId))
                .hasSize(1);

        currentActorId.set(driverId);
        mockMvc.perform(get("/api/v1/bookings/{bookingId}", paid.bookingId())
                        .with(user("driver").roles("DRIVER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.persistedStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.data.payment.status").value("REFUNDED"))
                .andExpect(jsonPath("$.data.payment.packageRefundedAmount").value(paid.amount()))
                .andExpect(jsonPath("$.data.refunds[0].status").value("SUCCEEDED"));

        assertFinancialInvariant(paid);
    }

    @Test
    @DisplayName("Outside grace requires fresh zero-refund consent; replay creates no obligation")
    void outsideGraceStaleConsentReturns409ThenZeroConsentCancelsWithoutRefund() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT.plusSeconds(7200));
        jdbc.update("""
                UPDATE bookings
                SET payment_confirmed_at = ?, free_cancellation_deadline = ?
                WHERE id = ?
                """, Timestamp.from(NOW.minusSeconds(1200)), Timestamp.from(NOW.minusSeconds(600)),
                paid.bookingId());

        cancel(paid, UUID.randomUUID(), paid.amount(), 409);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, paid.bookingId()))
                .isEqualTo("CONFIRMED");

        UUID zeroConsentKey = UUID.randomUUID();
        JsonNode cancelled = cancel(paid, zeroConsentKey, 0L, 200);
        assertThat(cancelled.at("/data/status").asText()).isEqualTo("CANCELLED");
        cancel(paid, zeroConsentKey, 0L, 200);

        assertThat(refundRepository.findByBookingIdOrderByCreatedAtAscIdAsc(paid.bookingId()))
                .isEmpty();
        assertThat(paymentRepository.findById(paid.paymentId())).get()
                .extracting(payment -> payment.getStatus())
                .isEqualTo(PaymentStatus.PAID);
        assertFinancialInvariant(paid);
    }

    @Test
    @DisplayName("Two cancels and two automatic dispatches still create one obligation and one success")
    void concurrentCancellationAndExecutionKeepSingleRefundSuccess() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT.plusSeconds(10800));
        CountDownLatch cancelReady = new CountDownLatch(2);
        CountDownLatch cancelStart = new CountDownLatch(1);

        List<Integer> cancelStatuses;
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = pool.submit(() -> concurrentCancel(
                    paid, UUID.randomUUID(), cancelReady, cancelStart));
            Future<Integer> second = pool.submit(() -> concurrentCancel(
                    paid, UUID.randomUUID(), cancelReady, cancelStart));
            assertThat(cancelReady.await(5, TimeUnit.SECONDS)).isTrue();
            cancelStart.countDown();
            cancelStatuses = List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS)
            );
        }
        assertThat(cancelStatuses).contains(200);
        assertThat(cancelStatuses).allMatch(code -> code == 200 || code == 409);

        var refunds = refundRepository.findByBookingIdOrderByCreatedAtAscIdAsc(paid.bookingId());
        assertThat(refunds).singleElement();
        UUID refundId = refunds.getFirst().getId();

        CountDownLatch executeReady = new CountDownLatch(2);
        CountDownLatch executeStart = new CountDownLatch(1);
        List<Boolean> executeResults;
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = pool.submit(() -> concurrentExecute(
                    refundId, executeReady, executeStart));
            Future<Boolean> second = pool.submit(() -> concurrentExecute(
                    refundId, executeReady, executeStart));
            assertThat(executeReady.await(5, TimeUnit.SECONDS)).isTrue();
            executeStart.countDown();
            executeResults = List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS)
            );
        }
        assertThat(executeResults).containsExactlyInAnyOrder(true, false);

        assertThat(refundRepository.findById(refundId)).get()
                .extracting(refund -> refund.getStatus())
                .isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(refundAttemptRepository.findByRefundIdOrderBySequenceNoAsc(refundId)
                .stream()
                .filter(attempt -> attempt.getStatus() == RefundAttemptStatus.SUCCEEDED))
                .hasSize(1);
        assertFinancialInvariant(paid);
    }

    @Test
    @DisplayName("Cancel-unpaid versus receipt race never revives a cancelled hold or over-refunds")
    void cancelUnpaidVersusReceiptRaceIsFinanciallySafe() throws Exception {
        PendingBooking pending = createPendingBookingWithCheckout(START_AT.plusSeconds(14400));
        currentActorId.set(driverId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        NormalizedReceipt receipt = new NormalizedReceipt(
                "SIMULATOR",
                "SIMULATOR",
                "TX-RACE-" + UUID.randomUUID(),
                BigDecimal.valueOf(pending.amount()),
                "VND",
                NOW.minusSeconds(1),
                NOW,
                pending.vaNumber(),
                pending.paymentCode(),
                "BKG-038 cancel/payment race",
                "{\"source\":\"BKG-038\"}"
        );

        int cancelStatus;
        PaymentReceiptResult receiptResult;
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> cancelFuture = pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return mockMvc.perform(post("/api/v1/bookings/{bookingId}/cancel", pending.bookingId())
                                .with(user("driver").roles("DRIVER"))
                                .header("Idempotency-Key", UUID.randomUUID())
                                .contentType("application/json")
                                .content(json(Map.of(
                                        "expectedVersion", 0,
                                        "expectedRefundAmount", 0,
                                        "acceptedPolicyVersion", POLICY_VERSION
                                ))))
                        .andReturn().getResponse().getStatus();
            });
            Future<PaymentReceiptResult> receiptFuture = pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return paymentConfirmationService.processReceipt(receipt);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            cancelStatus = cancelFuture.get(30, TimeUnit.SECONDS);
            receiptResult = receiptFuture.get(30, TimeUnit.SECONDS);
        }

        assertThat(cancelStatus).isIn(200, 409);
        assertThat(receiptResult.status()).isIn(
                PaymentReceiptResult.Status.APPLIED,
                PaymentReceiptResult.Status.UNAPPLIED
        );

        String bookingStatus = jdbc.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, pending.bookingId());
        String paymentStatus = jdbc.queryForObject(
                "SELECT status FROM payments WHERE id = ?", String.class, pending.paymentId());
        if (cancelStatus == 200) {
            assertThat(bookingStatus).isEqualTo("CANCELLED");
            assertThat(receiptResult.status()).isEqualTo(PaymentReceiptResult.Status.UNAPPLIED);
            assertThat(paymentStatus).isEqualTo("PENDING");
        } else {
            assertThat(bookingStatus).isEqualTo("CONFIRMED");
            assertThat(receiptResult.status()).isEqualTo(PaymentReceiptResult.Status.APPLIED);
            assertThat(paymentStatus).isEqualTo("PAID");
        }

        assertThat(refundRepository.findByBookingIdOrderByCreatedAtAscIdAsc(pending.bookingId()))
                .isEmpty();
        assertThat(sumApplied(pending.paymentId())).isLessThanOrEqualTo(pending.amount());
    }

    @Test
    @DisplayName("Effective no-show projection keeps the persisted Payment PAID")
    void noShowReadProjectionDoesNotMarkPaymentFailed() throws Exception {
        PaidBooking paid = createAndPayBooking(START_AT.plusSeconds(18000));
        jdbc.update("UPDATE bookings SET check_in_deadline = ? WHERE id = ?",
                Timestamp.from(NOW.minusSeconds(1)), paid.bookingId());

        mockMvc.perform(get("/api/v1/bookings/{bookingId}", paid.bookingId())
                        .with(user("driver").roles("DRIVER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.persistedStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.stateReconciliationPending").value(true))
                .andExpect(jsonPath("$.data.cancellationReason").value("NO_SHOW"))
                .andExpect(jsonPath("$.data.payment.status").value("PAID"));

        assertThat(paymentRepository.findById(paid.paymentId())).get()
                .extracting(payment -> payment.getStatus())
                .isEqualTo(PaymentStatus.PAID);
    }

    private PaidBooking createAndPayBooking(Instant startAt) throws Exception {
        PendingBooking pending = createPendingBookingWithCheckout(startAt);
        JsonNode simulated = body(mockMvc.perform(post(
                                "/api/v1/bookings/{bookingId}/simulate-payment", pending.bookingId())
                        .with(user("driver").roles("DRIVER"))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json")
                        .content(json(Map.of(
                                "transactionRef", "TX-" + UUID.randomUUID(),
                                "outcome", "SUCCESS",
                                "amount", pending.amount(),
                                "currency", "VND",
                                "providerPaidAt", NOW.minusSeconds(1).toString()
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.booking.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.booking.payment.status").value("PAID"))
                .andReturn());

        assertThat(simulated.at("/data/receipt/classification").asText()).isEqualTo("APPLIED");
        return new PaidBooking(
                pending.bookingId(), pending.paymentId(), pending.amount(), 1L
        );
    }

    private PendingBooking createPendingBookingWithCheckout(Instant startAt) throws Exception {
        currentActorId.set(driverId);
        JsonNode preview = body(mockMvc.perform(post("/api/v1/bookings/price-preview")
                        .with(user("driver").roles("DRIVER"))
                        .contentType("application/json")
                        .content(json(Map.of(
                                "connectorId", connectorId,
                                "startAt", startAt,
                                "durationMin", 60
                        ))))
                .andExpect(status().isOk())
                .andReturn());
        long amount = preview.at("/data/totalAmount").asLong();
        String pricingVersion = preview.at("/data/pricingVersion").asText();
        String policyVersion = preview.at("/data/policy/policyVersion").asText();

        JsonNode created = body(mockMvc.perform(post("/api/v1/bookings")
                        .with(user("driver").roles("DRIVER"))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json")
                        .content(json(Map.of(
                                "connectorId", connectorId,
                                "startAt", startAt,
                                "durationMin", 60,
                                "acceptedTotalAmount", amount,
                                "acceptedPricingVersion", pricingVersion,
                                "acceptedPolicyVersion", policyVersion,
                                "paymentMethod", "SIMULATOR"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn());
        UUID bookingId = UUID.fromString(created.at("/data/bookingId").asText());
        UUID paymentId = UUID.fromString(created.at("/data/payment/paymentId").asText());

        mockMvc.perform(post(
                                "/api/v1/bookings/{bookingId}/checkout", bookingId)
                        .with(user("driver").roles("DRIVER"))
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andReturn();
        var payment = paymentRepository.findById(paymentId).orElseThrow();

        return new PendingBooking(
                bookingId,
                paymentId,
                amount,
                payment.getPaymentCode(),
                payment.getVaNumber()
        );
    }

    private JsonNode cancel(PaidBooking paid, UUID requestKey, long expectedRefund, int expectedStatus)
            throws Exception {
        var action = mockMvc.perform(post("/api/v1/bookings/{bookingId}/cancel", paid.bookingId())
                .with(user("driver").roles("DRIVER"))
                .header("Idempotency-Key", requestKey)
                .contentType("application/json")
                .content(json(Map.of(
                        "expectedVersion", paid.confirmedVersion(),
                        "expectedRefundAmount", expectedRefund,
                        "acceptedPolicyVersion", POLICY_VERSION
                ))));
        return body(action.andExpect(status().is(expectedStatus)).andReturn());
    }

    private int concurrentCancel(
            PaidBooking paid,
            UUID requestKey,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return mockMvc.perform(post("/api/v1/bookings/{bookingId}/cancel", paid.bookingId())
                        .with(user("driver").roles("DRIVER"))
                        .header("Idempotency-Key", requestKey)
                        .contentType("application/json")
                        .content(json(Map.of(
                                "expectedVersion", paid.confirmedVersion(),
                                "expectedRefundAmount", paid.amount(),
                                "acceptedPolicyVersion", POLICY_VERSION
                        ))))
                .andReturn().getResponse().getStatus();
    }

    private boolean concurrentExecute(
            UUID refundId,
            CountDownLatch ready,
            CountDownLatch start
    ) throws Exception {
        ready.countDown();
        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
        return automaticRefundExecutionService.processFirstAttempt(refundId);
    }

    private void assertFinancialInvariant(PaidBooking paid) {
        long applied = sumApplied(paid.paymentId());
        long refunded = jdbc.queryForObject(
                "SELECT COALESCE(refund_amount, 0) FROM payments WHERE id = ?",
                BigDecimal.class,
                paid.paymentId()
        ).longValueExact();
        assertThat(applied).isEqualTo(paid.amount());
        assertThat(refunded).isLessThanOrEqualTo(applied);
        assertThat(refundRepository.findByBookingIdOrderByCreatedAtAscIdAsc(paid.bookingId())
                .stream()
                .map(refund -> refund.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isLessThanOrEqualTo(BigDecimal.valueOf(applied));
    }

    private long sumApplied(UUID paymentId) {
        return paymentTransactionRepository.findByPaymentIdOrderByReceivedAtAscIdAsc(paymentId)
                .stream()
                .filter(transaction -> transaction.getApplicationClassification()
                        == PaymentApplicationClassification.APPLIED)
                .map(transaction -> transaction.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .longValueExact();
    }

    private JsonNode body(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private Fixture insertBookableStationFixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        UUID ownerId = UUID.randomUUID();
        UUID fixtureDriverId = UUID.randomUUID();
        UUID fixtureAdminId = UUID.randomUUID();
        UUID stationId = UUID.randomUUID();
        UUID chargePointId = UUID.randomUUID();
        UUID fixtureConnectorId = UUID.randomUUID();
        UUID scheduleId = UUID.randomUUID();

        insertUser(ownerId, "owner-" + suffix, "Owner " + suffix);
        insertUser(fixtureDriverId, "driver-" + suffix, "Driver " + suffix);
        insertUser(fixtureAdminId, "admin-" + suffix, "Admin " + suffix);

        jdbc.update("""
                INSERT INTO stations
                    (id, station_code, owner_id, name, address_line,
                     latitude, longitude, contact_phone, planned_charge_point_count,
                     status, operational_status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'BKG-038 Test Address',
                        10.7769, 106.7009, '0900000000', 1,
                        'ACTIVE', 'OPERATING', 0, ?, ?)
                """, stationId, "ST-BKG38-" + suffix, ownerId, "BKG-038 Station " + suffix,
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO licenses
                    (id, license_code, station_id, owner_id, plan, fee_amount,
                     start_at, expires_at, status, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'MONTHLY', 0, ?, ?, 'ACTIVE', 0, ?, ?)
                """, UUID.randomUUID(), "LIC-BKG38-" + suffix, stationId, ownerId,
                Timestamp.from(NOW.minusSeconds(3600)), Timestamp.from(NOW.plusSeconds(172800)),
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO station_booking_settings
                    (id, station_id, min_duration_minutes, duration_step_minutes,
                     max_duration_minutes, base_price_vnd, version, created_at, updated_at)
                VALUES (?, ?, 30, 30, 180, 3400, 0, ?, ?)
                """, UUID.randomUUID(), stationId, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO station_operating_schedules
                    (id, station_id, open_24_hours, effective_from, created_at, updated_at)
                VALUES (?, ?, true, ?, ?, ?)
                """, scheduleId, stationId, Timestamp.from(NOW.minusSeconds(3600)),
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO charge_points
                    (id, station_id, charge_point_code, max_power_kw,
                     provisioning_status, operational_status, version, created_at, updated_at)
                VALUES (?, ?, ?, 60, 'ACTIVE', 'AVAILABLE', 0, ?, ?)
                """, chargePointId, stationId, "CP-BKG38-" + suffix,
                Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO connectors
                    (id, charge_point_id, connector_code, connector_type, power_kw,
                     charger_type, runtime_status, version, created_at, updated_at)
                VALUES (?, ?, ?, 'CCS2', 60, 'DC', 'AVAILABLE', 0, ?, ?)
                """, fixtureConnectorId, chargePointId, "CON-BKG38-" + suffix,
                Timestamp.from(NOW), Timestamp.from(NOW));

        return new Fixture(fixtureDriverId, fixtureAdminId, fixtureConnectorId);
    }

    private void insertUser(UUID id, String keycloakId, String displayName) {
        jdbc.update("""
                INSERT INTO user_profile
                    (id, keycloak_id, email, display_name, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)
                """, id, keycloakId, keycloakId + "@example.test", displayName,
                Timestamp.from(NOW), Timestamp.from(NOW));
    }

    private record Fixture(UUID driverId, UUID adminId, UUID connectorId) {
    }

    private record PendingBooking(
            UUID bookingId,
            UUID paymentId,
            long amount,
            String paymentCode,
            String vaNumber
    ) {
    }

    private record PaidBooking(
            UUID bookingId,
            UUID paymentId,
            long amount,
            long confirmedVersion
    ) {
    }
}
