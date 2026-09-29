package com.thang.chargeops.booking;

import com.thang.chargeops.booking.dto.filter.OwnerActiveBookingFilter;
import com.thang.chargeops.booking.dto.filter.OwnerBookingFilter;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.policy.BookingEffectiveStatePolicy;
import com.thang.chargeops.booking.repository.OwnerBookingReadRepository;
import com.thang.chargeops.booking.repository.specs.OwnerBookingPredicateFactory;
import com.thang.chargeops.common.enums.BookingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
        OwnerBookingPredicateFactory.class,
        BookingEffectiveStatePolicy.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OwnerBookingRepositoryPostgresTest {

    private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("126000.00");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                    .asCompatibleSubstituteFor("postgres")
    );

    @Autowired private JdbcTemplate jdbc;
    @Autowired private OwnerBookingReadRepository readRepository;
    @Autowired private OwnerBookingPredicateFactory predicateFactory;
    @Autowired private BookingEffectiveStatePolicy effectiveStatePolicy;

    private UUID ownerAId;
    private UUID ownerBId;
    private UUID stationAId;
    private UUID stationBId;
    private UUID connectorA1Id;
    private UUID connectorA2Id;
    private UUID connectorBId;
    private UUID driverId;

    @BeforeEach
    void setUpData() {
        jdbc.execute("DELETE FROM booking_price_lines");
        jdbc.execute("DELETE FROM booking_status_history");
        jdbc.execute("DELETE FROM payments");
        jdbc.execute("DELETE FROM bookings");
        jdbc.execute("DELETE FROM connectors");
        jdbc.execute("DELETE FROM charge_points");
        jdbc.execute("DELETE FROM stations");
        jdbc.execute("DELETE FROM user_profile");

        ownerAId = insertProfile("owner-a");
        ownerBId = insertProfile("owner-b");
        driverId = insertProfile("driver");

        stationAId = insertStation(ownerAId, "Station A");
        stationBId = insertStation(ownerBId, "Station B");

        UUID cpAId = insertChargePoint(stationAId, "CP-A");
        UUID cpBId = insertChargePoint(stationBId, "CP-B");

        connectorA1Id = insertConnector(cpAId, "CON-A1");
        connectorA2Id = insertConnector(cpAId, "CON-A2");
        connectorBId = insertConnector(cpBId, "CON-B");
    }

    @Test
    void ownerOnlySeesBookingsFromTheirOwnStationsWhenFilterUnspecified() {
        UUID bookingA = insertBooking(ownerAId, connectorA1Id, BookingStatus.CONFIRMED,
                NOW.plusSeconds(3600), NOW.plusSeconds(7200), null, NOW.plusSeconds(3600 + 900));
        UUID bookingB = insertBooking(ownerBId, connectorBId, BookingStatus.CONFIRMED,
                NOW.plusSeconds(3600), NOW.plusSeconds(7200), null, NOW.plusSeconds(3600 + 900));

        OwnerBookingFilter filter = new OwnerBookingFilter(null, null, null, null, null);
        var spec = predicateFactory.forList(ownerAId, filter, NOW);
        var page = readRepository.findAll(spec, PageRequest.of(0, 20));

        List<UUID> foundIds = page.getContent().stream().map(Booking::getId).toList();
        assertThat(foundIds).contains(bookingA).doesNotContain(bookingB);
    }

    @Test
    void filterByConnectorScopesCorrectly() {
        UUID bookingA1 = insertBooking(ownerAId, connectorA1Id, BookingStatus.CONFIRMED,
                NOW.plusSeconds(3600), NOW.plusSeconds(7200), null, NOW.plusSeconds(3600 + 900));
        UUID bookingA2 = insertBooking(ownerAId, connectorA2Id, BookingStatus.CONFIRMED,
                NOW.plusSeconds(3600), NOW.plusSeconds(7200), null, NOW.plusSeconds(3600 + 900));

        OwnerBookingFilter filter = new OwnerBookingFilter(stationAId, connectorA1Id, null, null, null);
        var spec = predicateFactory.forList(ownerAId, filter, NOW);
        var page = readRepository.findAll(spec, PageRequest.of(0, 20));

        List<UUID> foundIds = page.getContent().stream().map(Booking::getId).toList();
        assertThat(foundIds).containsExactly(bookingA1);
    }

    @Test
    void timeWindowOverlapHalfOpenSemantics() {
        Instant windowFrom = NOW.plusSeconds(3600);
        Instant windowTo = NOW.plusSeconds(7200);

        // Abuts windowFrom: [NOW, windowFrom] -> endAt == windowFrom (not > windowFrom) -> excluded
        UUID bAbutLeft = insertBooking(ownerAId, connectorA1Id, BookingStatus.COMPLETED,
                NOW, windowFrom, null, null);

        // Abuts windowTo: [windowTo, windowTo + 3600] -> startAt == windowTo (not < windowTo) -> excluded
        UUID bAbutRight = insertBooking(ownerAId, connectorA1Id, BookingStatus.CONFIRMED,
                windowTo, windowTo.plusSeconds(3600), null, windowTo.plusSeconds(900));

        // Strictly overlapping: [windowFrom + 900, windowTo - 900] -> included
        UUID bOverlap = insertBooking(ownerAId, connectorA2Id, BookingStatus.CONFIRMED,
                windowFrom.plusSeconds(900), windowTo.minusSeconds(900), null, windowFrom.plusSeconds(1800));

        OwnerBookingFilter filter = new OwnerBookingFilter(stationAId, null, windowFrom, windowTo, null);
        var spec = predicateFactory.forList(ownerAId, filter, NOW);
        var page = readRepository.findAll(spec, PageRequest.of(0, 20));

        List<UUID> foundIds = page.getContent().stream().map(Booking::getId).toList();
        assertThat(foundIds).containsExactly(bOverlap);
        assertThat(foundIds).doesNotContain(bAbutLeft, bAbutRight);
    }

    @Test
    void effectiveStatusFilterMatchesOverduePendingAsExpiredAndOverdueConfirmedAsCancelled() {
        // PENDING overdue: expiresAt <= NOW
        UUID overduePending = insertBooking(ownerAId, connectorA1Id, BookingStatus.PENDING,
                NOW.plusSeconds(3600), NOW.plusSeconds(7200), NOW.minusSeconds(60), null);

        // CONFIRMED overdue: checkInDeadline <= NOW
        UUID overdueConfirmed = insertBooking(ownerAId, connectorA1Id, BookingStatus.CONFIRMED,
                NOW.minusSeconds(3600), NOW.minusSeconds(1800), null, NOW.minusSeconds(60));

        // Live PENDING: expiresAt > NOW
        UUID livePending = insertBooking(ownerAId, connectorA2Id, BookingStatus.PENDING,
                NOW.plusSeconds(3600), NOW.plusSeconds(7200), NOW.plusSeconds(300), null);

        // Live CONFIRMED: checkInDeadline > NOW
        UUID liveConfirmed = insertBooking(ownerAId, connectorA2Id, BookingStatus.CONFIRMED,
                NOW.plusSeconds(7200), NOW.plusSeconds(10800), null, NOW.plusSeconds(8100));

        // Filter status = EXPIRED should match overduePending
        var expiredSpec = predicateFactory.forList(ownerAId,
                new OwnerBookingFilter(stationAId, null, null, null, BookingStatus.EXPIRED), NOW);
        List<UUID> expiredResults = readRepository.findAll(expiredSpec, PageRequest.of(0, 20))
                .getContent().stream().map(Booking::getId).toList();
        assertThat(expiredResults).contains(overduePending).doesNotContain(livePending);

        // Filter status = CANCELLED should match overdueConfirmed
        var cancelledSpec = predicateFactory.forList(ownerAId,
                new OwnerBookingFilter(stationAId, null, null, null, BookingStatus.CANCELLED), NOW);
        List<UUID> cancelledResults = readRepository.findAll(cancelledSpec, PageRequest.of(0, 20))
                .getContent().stream().map(Booking::getId).toList();
        assertThat(cancelledResults).contains(overdueConfirmed).doesNotContain(liveConfirmed);

        // Filter status = PENDING should match only livePending, not overduePending
        var pendingSpec = predicateFactory.forList(ownerAId,
                new OwnerBookingFilter(stationAId, null, null, null, BookingStatus.PENDING), NOW);
        List<UUID> pendingResults = readRepository.findAll(pendingSpec, PageRequest.of(0, 20))
                .getContent().stream().map(Booking::getId).toList();
        assertThat(pendingResults).contains(livePending).doesNotContain(overduePending);

        // Filter status = CONFIRMED should match only liveConfirmed, not overdueConfirmed
        var confirmedSpec = predicateFactory.forList(ownerAId,
                new OwnerBookingFilter(stationAId, null, null, null, BookingStatus.CONFIRMED), NOW);
        List<UUID> confirmedResults = readRepository.findAll(confirmedSpec, PageRequest.of(0, 20))
                .getContent().stream().map(Booking::getId).toList();
        assertThat(confirmedResults).contains(liveConfirmed).doesNotContain(overdueConfirmed);
    }

    @Test
    void activeForReturnsFourBlockerStatesAndExcludesOverdueHoldOrTerminal() {
        // Blockers:
        // livePending on connectorA1: [NOW + 3600, NOW + 7200]
        UUID livePending = insertBooking(ownerAId, connectorA1Id, BookingStatus.PENDING,
                NOW.plusSeconds(3600), NOW.plusSeconds(7200), NOW.plusSeconds(300), null);
        // liveConfirmed on connectorA1: [NOW + 7200, NOW + 10800]
        UUID liveConfirmed = insertBooking(ownerAId, connectorA1Id, BookingStatus.CONFIRMED,
                NOW.plusSeconds(7200), NOW.plusSeconds(10800), null, NOW.plusSeconds(8100));
        // checkedIn on connectorA1: [NOW - 1800, NOW + 1800]
        UUID checkedIn = insertBooking(ownerAId, connectorA1Id, BookingStatus.CHECKED_IN,
                NOW.minusSeconds(1800), NOW.plusSeconds(1800), null, null);
        // chargingPastEnd on connectorA1: [NOW - 7200, NOW - 3600]
        UUID chargingPastEnd = insertBooking(ownerAId, connectorA1Id, BookingStatus.CHARGING,
                NOW.minusSeconds(7200), NOW.minusSeconds(3600), null, null);

        // Non-blockers on connectorA1 with non-overlapping past/future slots:
        // overduePending: [NOW + 14400, NOW + 18000], expiresAt <= NOW
        UUID overduePending = insertBooking(ownerAId, connectorA1Id, BookingStatus.PENDING,
                NOW.plusSeconds(14400), NOW.plusSeconds(18000), NOW.minusSeconds(60), null);
        // overdueConfirmed: [NOW - 10800, NOW - 7200], checkInDeadline <= NOW
        UUID overdueConfirmed = insertBooking(ownerAId, connectorA1Id, BookingStatus.CONFIRMED,
                NOW.minusSeconds(10800), NOW.minusSeconds(7200), null, NOW.minusSeconds(60));
        // completed: [NOW - 14400, NOW - 10800]
        UUID completed = insertBooking(ownerAId, connectorA1Id, BookingStatus.COMPLETED,
                NOW.minusSeconds(14400), NOW.minusSeconds(10800), null, null);
        // cancelled: [NOW - 18000, NOW - 14400]
        UUID cancelled = insertBooking(ownerAId, connectorA1Id, BookingStatus.CANCELLED,
                NOW.minusSeconds(18000), NOW.minusSeconds(14400), null, null);

        OwnerActiveBookingFilter filter = new OwnerActiveBookingFilter(stationAId, null, connectorA1Id);
        var spec = predicateFactory.forActive(ownerAId, filter, NOW);
        var results = readRepository.findAll(spec, PageRequest.of(0, 20, Sort.by("startAt").ascending()))
                .getContent().stream().map(Booking::getId).toList();

        assertThat(results).contains(livePending, liveConfirmed, checkedIn, chargingPastEnd);
        assertThat(results).doesNotContain(overduePending, overdueConfirmed, completed, cancelled);
    }

    @Test
    void findOwnerDetailLoadsOwnedBookingAndRejectsForeignOwner() {
        UUID bookingA = insertBooking(ownerAId, connectorA1Id, BookingStatus.CONFIRMED,
                NOW.plusSeconds(3600), NOW.plusSeconds(7200), null, NOW.plusSeconds(4500));
        insertPriceLine(bookingA, 1, NOW.plusSeconds(3600), NOW.plusSeconds(7200), AMOUNT);

        var ownedDetail = readRepository.findOwnerDetail(bookingA, ownerAId);
        assertThat(ownedDetail).isPresent();
        assertThat(ownedDetail.get().getPriceLines()).hasSize(1);
        assertThat(ownedDetail.get().getConnector().getChargePoint().getStation().getOwner().getId())
                .isEqualTo(ownerAId);

        var foreignDetail = readRepository.findOwnerDetail(bookingA, ownerBId);
        assertThat(foreignDetail).isEmpty();
    }

    private UUID insertProfile(String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_profile(id, keycloak_id, email) VALUES (?, ?, ?)",
                id, prefix + "-" + id, prefix + "-" + id + "@chargeops.test");
        return id;
    }

    private UUID insertStation(UUID ownerId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO stations(id, owner_id, name, address_line, contact_phone, status) VALUES (?, ?, ?, '123 Test St', '0123456789', 'ACTIVE')",
                id, ownerId, name);
        return id;
    }

    private UUID insertChargePoint(UUID stationId, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO charge_points(id, station_id, charge_point_code, provisioning_status, operational_status) VALUES (?, ?, ?, 'ACTIVE', 'AVAILABLE')",
                id, stationId, code + "-" + id.toString().substring(0, 6));
        return id;
    }

    private UUID insertConnector(UUID chargePointId, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO connectors(id, charge_point_id, connector_code, connector_type, power_kw, charger_type, runtime_status) VALUES (?, ?, ?, 'CCS2', 60.0, 'DC', 'AVAILABLE')",
                id, chargePointId, code + "-" + id.toString().substring(0, 6));
        return id;
    }

    private UUID insertBooking(UUID ownerId, UUID connectorId, BookingStatus status,
                              Instant startAt, Instant endAt, Instant expiresAt, Instant checkInDeadline) {
        UUID id = UUID.randomUUID();
        String code = "BK-" + id.toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO bookings(
                    id, driver_id, connector_id, booking_code, start_at, end_at, status,
                    total_amount, station_name_snapshot, station_address_snapshot,
                    charge_point_code_snapshot, connector_code_snapshot, expires_at,
                    policy_version, payment_confirmed_at, free_cancellation_deadline,
                    check_in_deadline, version
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'Station', 'Address', 'CP', 'CON', ?, 'booking-v4.9', ?, ?, ?, 0)
                """,
                id, driverId, connectorId, code,
                Timestamp.from(startAt), Timestamp.from(endAt), status.name(), AMOUNT,
                expiresAt != null ? Timestamp.from(expiresAt) : null,
                Timestamp.from(NOW),
                Timestamp.from(startAt.minusSeconds(1800)),
                checkInDeadline != null ? Timestamp.from(checkInDeadline) : null
        );
        return id;
    }

    private void insertPriceLine(UUID bookingId, int seq, Instant start, Instant end, BigDecimal amount) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO booking_price_lines(
                    id, booking_id, sequence, segment_start, segment_end, duration_minutes,
                    label, period_code, rate_vnd_per_kwh, estimated_energy_kwh, power_kw,
                    energy_factor, formula_version, amount, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, 60, 'Peak', 'PEAK', 3800.00, 30.000, 60.00, 0.620000, 'v1', ?, ?, ?)
                """,
                id, bookingId, seq, Timestamp.from(start), Timestamp.from(end), amount,
                Timestamp.from(NOW), Timestamp.from(NOW)
        );
    }
}
