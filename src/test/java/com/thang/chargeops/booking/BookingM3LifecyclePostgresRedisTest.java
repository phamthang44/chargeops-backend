package com.thang.chargeops.booking;

import com.thang.chargeops.booking.checkin.CheckInChallengeRepository;
import com.thang.chargeops.booking.command.BookingCommandInFlightLock;
import com.thang.chargeops.booking.history.BookingStatusHistoryRepository;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.service.BookingAutomaticCompletionService;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.common.enums.RuntimeStatus;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.station.repository.ConnectorRepository;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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
@DisplayName("BKG-046: M3 check-in/session acceptance with PostgreSQL and Redis")
class BookingM3LifecyclePostgresRedisTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-28T03:00:00Z");
    private static final Instant START_AT = Instant.parse("2026-09-29T16:30:00Z");
    private static final Instant END_AT = Instant.parse("2026-09-29T17:30:00Z");
    private static final Instant CHECK_IN_DEADLINE = END_AT.minus(Duration.ofMinutes(15));
    private static final BigDecimal AMOUNT = new BigDecimal("120000.00");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                    .asCompatibleSubstituteFor("postgres")
    );

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine")
    ).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserProfileRepository userProfileRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private ConnectorRepository connectorRepository;
    @Autowired private BookingStatusHistoryRepository historyRepository;
    @Autowired private CheckInChallengeRepository challengeRepository;
    @Autowired private BookingAutomaticCompletionService completionService;

    @MockitoBean private CurrentProfileProvider currentProfileProvider;
    @MockitoBean private Clock applicationClock;
    @MockitoBean private BookingCommandInFlightLock bookingCommandInFlightLock;
    private final AtomicReference<Instant> now = new AtomicReference<>(CREATED_AT);
    private UUID driverId;

    @BeforeEach
    void setUp() {
        when(applicationClock.instant()).thenAnswer(invocation -> now.get());
        when(applicationClock.getZone()).thenReturn(ZoneOffset.UTC);
        when(applicationClock.withZone(any())).thenReturn(applicationClock);
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get())
                .when(bookingCommandInFlightLock)
                .executeWithLock(any(), any(), any(), any());
        when(currentProfileProvider.requireProfile()).thenAnswer(invocation ->
                userProfileRepository.findById(driverId).orElseThrow());
    }

    @Test
    @DisplayName("real simulator QR drives tomorrow-across-midnight lifecycle and delayed completion")
    void realQrDrivesFullLifecycleAcrossMidnightAndDelayedCompletion() throws Exception {
        Fixture fixture = createFixture();
        driverId = fixture.driverId();
        now.set(START_AT);

        String token = issueChallenge(fixture.connectorId());
        JsonNode resolved = body(mockMvc.perform(post("/api/v1/bookings/check-in/resolve")
                        .with(user("driver").roles("DRIVER"))
                        .contentType("application/json")
                        .content(json(Map.of(
                                "bookingId", fixture.bookingId(),
                                "challengeToken", token
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.startAt").value(START_AT.toString()))
                .andExpect(jsonPath("$.data.endAt").value(END_AT.toString()))
                .andExpect(jsonPath("$.data.checkInDeadline").value(CHECK_IN_DEADLINE.toString()))
                .andReturn());

        long confirmedVersion = resolved.at("/data/expectedVersion").asLong();
        UUID confirmKey = UUID.randomUUID();
        JsonNode checkedIn = confirm(fixture.bookingId(), confirmKey, confirmedVersion, token, 200);
        assertThat(checkedIn.at("/data/status").asText()).isEqualTo("CHECKED_IN");

        JsonNode replay = confirm(fixture.bookingId(), confirmKey, confirmedVersion, token, 200);
        assertThat(replay.at("/data/version").asLong())
                .isEqualTo(checkedIn.at("/data/version").asLong());

        long checkedInVersion = checkedIn.at("/data/version").asLong();
        now.set(START_AT.plusSeconds(1));
        JsonNode charging = body(mockMvc.perform(post(
                                "/api/v1/bookings/{bookingId}/start-charging", fixture.bookingId())
                        .with(user("driver").roles("DRIVER"))
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json")
                        .content(json(Map.of("expectedVersion", checkedInVersion))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CHARGING"))
                .andReturn());
        assertThat(charging.at("/data/version").asLong()).isGreaterThan(checkedInVersion);

        Instant delayedDecision = END_AT.plusSeconds(90);
        now.set(delayedDecision);
        assertThat(completionService.completeIfDue(
                fixture.bookingId(), fixture.connectorId(), delayedDecision
        )).isTrue();

        var completed = bookingRepository.findById(fixture.bookingId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(completed.getCompletedAt()).isEqualTo(delayedDecision);
        assertThat(completed.getStartAt()).isEqualTo(START_AT);
        assertThat(completed.getEndAt()).isEqualTo(END_AT);
        assertThat(completed.getTotalAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(connectorRepository.findById(fixture.connectorId()).orElseThrow().getRuntimeStatus())
                .isEqualTo(RuntimeStatus.AVAILABLE);
        assertThat(historyRepository.findByBooking_IdOrderByOccurredAtAscIdAsc(fixture.bookingId()))
                .extracting(history -> history.getReason())
                .containsExactly("CHECK_IN_CONFIRMED", "CHARGING_STARTED", "SESSION_COMPLETED");
    }

    @Test
    @DisplayName("DB rollback after Redis consume keeps booking unchanged; a fresh QR can retry")
    void rollbackAfterConsumeRequiresFreshQrAndRetrySucceeds() throws Exception {
        Fixture fixture = createFixture();
        driverId = fixture.driverId();
        now.set(START_AT);
        String consumedToken = issueChallenge(fixture.connectorId());

        jdbc.execute("""
                ALTER TABLE booking_status_history
                ADD CONSTRAINT bkg046_force_rollback
                CHECK (reason <> 'CHECK_IN_CONFIRMED')
                """);
        try {
            confirm(fixture.bookingId(), UUID.randomUUID(), 0L, consumedToken, 409);
        } finally {
            jdbc.execute("""
                    ALTER TABLE booking_status_history
                    DROP CONSTRAINT bkg046_force_rollback
                    """);
        }

        assertThat(challengeRepository.findConnectorId(consumedToken)).isEmpty();
        assertThat(bookingRepository.findById(fixture.bookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(connectorRepository.findById(fixture.connectorId()).orElseThrow().getRuntimeStatus())
                .isEqualTo(RuntimeStatus.AVAILABLE);
        assertThat(historyRepository.findByBooking_IdOrderByOccurredAtAscIdAsc(fixture.bookingId()))
                .isEmpty();

        String freshToken = issueChallenge(fixture.connectorId());
        JsonNode retried = confirm(
                fixture.bookingId(), UUID.randomUUID(), 0L, freshToken, 200
        );
        assertThat(retried.at("/data/status").asText()).isEqualTo("CHECKED_IN");
    }

    @Test
    @DisplayName("wrong-connector and expired QR attempts never mutate the booking")
    void wrongConnectorAndExpiredQrNeverMutateBooking() throws Exception {
        Fixture fixture = createFixture();
        driverId = fixture.driverId();
        now.set(START_AT);

        String wrongToken = issueChallenge(fixture.otherConnectorId());
        confirm(fixture.bookingId(), UUID.randomUUID(), 0L, wrongToken, 400);
        assertThat(challengeRepository.findConnectorId(wrongToken))
                .contains(fixture.otherConnectorId());

        String expiredToken = "expired-" + UUID.randomUUID();
        challengeRepository.save(
                expiredToken,
                fixture.connectorId(),
                Duration.ofMillis(50)
        );
        Thread.sleep(150);
        confirm(fixture.bookingId(), UUID.randomUUID(), 0L, expiredToken, 400);

        var unchanged = bookingRepository.findById(fixture.bookingId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(unchanged.getStartAt()).isEqualTo(START_AT);
        assertThat(unchanged.getEndAt()).isEqualTo(END_AT);
        assertThat(unchanged.getTotalAmount()).isEqualByComparingTo(AMOUNT);
    }

    private JsonNode confirm(
            UUID bookingId,
            UUID requestKey,
            long expectedVersion,
            String token,
            int expectedStatus
    ) throws Exception {
        return body(mockMvc.perform(post("/api/v1/bookings/{bookingId}/check-in", bookingId)
                        .with(user("driver").roles("DRIVER"))
                        .header("Idempotency-Key", requestKey)
                        .contentType("application/json")
                        .content(json(Map.of(
                                "expectedVersion", expectedVersion,
                                "challengeToken", token
                        ))))
                .andExpect(status().is(expectedStatus))
                .andReturn());
    }

    private String issueChallenge(UUID connectorId) throws Exception {
        JsonNode response = body(mockMvc.perform(post(
                                "/api/v1/internal/connectors/{connectorId}/check-in-challenge",
                                connectorId)
                        .with(user("driver").roles("DRIVER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresInSeconds").value(60))
                .andReturn());
        return response.at("/data/challengeToken").asText();
    }

    private JsonNode body(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private Fixture createFixture() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String provinceCode = suffix.substring(0, 4).toUpperCase();
        String wardCode = suffix.substring(4, 10).toUpperCase();
        UUID ownerId = UUID.randomUUID();
        UUID fixtureDriverId = UUID.randomUUID();
        UUID stationId = UUID.randomUUID();
        UUID chargePointId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();
        UUID otherConnectorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        jdbc.update("INSERT INTO provinces(code, name, full_name) VALUES (?, ?, ?)",
                provinceCode, "Province " + suffix, "Province " + suffix);
        jdbc.update("INSERT INTO wards(code, name, full_name, province_code) VALUES (?, ?, ?, ?)",
                wardCode, "Ward " + suffix, "Ward " + suffix, provinceCode);
        jdbc.update("""
                INSERT INTO user_profile(id, keycloak_id, email)
                VALUES (?, ?, ?), (?, ?, ?)
                """,
                ownerId, "owner-" + suffix, "owner-" + suffix + "@example.test",
                fixtureDriverId, "driver-" + suffix, "driver-" + suffix + "@example.test");
        jdbc.update("""
                INSERT INTO stations(
                    id, station_code, owner_id, name, address_line, ward_code,
                    latitude, longitude, contact_phone, planned_charge_point_count,
                    status, operational_status, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'BKG-046 Test Address', ?, 10.0, 106.0,
                          '0900000000', 1, 'ACTIVE', 'OPERATING', 0, ?, ?)
                """, stationId, "ST-" + suffix, ownerId, "M3 Station " + suffix,
                wardCode, Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT));
        jdbc.update("""
                INSERT INTO charge_points(
                    id, station_id, charge_point_code, max_power_kw,
                    provisioning_status, operational_status, version, created_at, updated_at
                ) VALUES (?, ?, ?, 60.0, 'ACTIVE', 'AVAILABLE', 0, ?, ?)
                """, chargePointId, stationId, "CP-" + suffix,
                Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT));
        insertConnector(connectorId, chargePointId, "CON-A-" + suffix);
        insertConnector(otherConnectorId, chargePointId, "CON-B-" + suffix);
        jdbc.update("""
                INSERT INTO bookings(
                    id, driver_id, connector_id, booking_code, start_at, end_at, status,
                    total_amount, station_name_snapshot, station_address_snapshot,
                    charge_point_code_snapshot, connector_code_snapshot, expires_at,
                    payment_confirmed_at, free_cancellation_deadline, check_in_deadline,
                    version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'CONFIRMED', ?, 'M3 Station',
                          'BKG-046 Test Address', 'CP-M3', 'CON-M3', ?, ?, ?, ?, 0, ?, ?)
                """,
                bookingId, fixtureDriverId, connectorId, "BK-" + suffix.toUpperCase(),
                Timestamp.from(START_AT), Timestamp.from(END_AT), AMOUNT,
                Timestamp.from(CREATED_AT.plusSeconds(600)),
                Timestamp.from(CREATED_AT.plusSeconds(600)),
                Timestamp.from(CREATED_AT.plusSeconds(1200)),
                Timestamp.from(CHECK_IN_DEADLINE),
                Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT));
        jdbc.update("""
                INSERT INTO payments(
                    id, booking_id, amount, status, method, refund_amount, paid_at,
                    provider, receiving_account_ref, currency, environment, version,
                    needs_reconciliation, payment_code, provider_order_ref,
                    va_number, provider_expires_at
                ) VALUES (?, ?, ?, 'PAID', 'SIMULATOR', 0, ?, 'SIMULATOR', ?, 'VND', 'SIMULATOR', 0,
                          false, ?, ?, ?, ?)
                """,
                paymentId, bookingId, AMOUNT, Timestamp.from(CREATED_AT.plusSeconds(600)),
                "ACC-" + suffix, "CO" + suffix.toUpperCase(), "ORDER-" + suffix,
                "VA" + suffix.toUpperCase(), Timestamp.from(END_AT));

        return new Fixture(fixtureDriverId, connectorId, otherConnectorId, bookingId);
    }

    private void insertConnector(UUID connectorId, UUID chargePointId, String code) {
        jdbc.update("""
                INSERT INTO connectors(
                    id, charge_point_id, connector_code, connector_type, power_kw,
                    charger_type, runtime_status, version, created_at, updated_at
                ) VALUES (?, ?, ?, 'CCS2', 60.0, 'DC', 'AVAILABLE', 0, ?, ?)
                """, connectorId, chargePointId, code,
                Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT));
    }

    private record Fixture(
            UUID driverId,
            UUID connectorId,
            UUID otherConnectorId,
            UUID bookingId
    ) {
    }
}
