package com.thang.chargeops.booking;

import com.thang.chargeops.booking.dto.filter.StationOperationalBookingFilter;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.policy.BookingEffectiveStatePolicy;
import com.thang.chargeops.booking.repository.StationOperationalBookingReadRepository;
import com.thang.chargeops.booking.repository.specs.StationOperationalBookingPredicateFactory;
import com.thang.chargeops.common.enums.BookingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
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
        StationOperationalBookingPredicateFactory.class,
        BookingEffectiveStatePolicy.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StationOperationalBookingPostgresTest {

    private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("126000.00");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                    .asCompatibleSubstituteFor("postgres")
    );

    @Autowired private JdbcTemplate jdbc;
    @Autowired private StationOperationalBookingReadRepository readRepository;
    @Autowired private StationOperationalBookingPredicateFactory predicateFactory;
    @Autowired private BookingEffectiveStatePolicy effectiveStatePolicy;

    private UUID ownerAId;
    private UUID ownerBId;
    private UUID staffId;
    private UUID driverId;
    private UUID stationAId;
    private UUID stationBId;
    private UUID connectorA1Id;
    private UUID connectorA2Id;
    private UUID connectorBId;

    @BeforeEach
    void setUpData() {
        jdbc.execute("DELETE FROM booking_price_lines");
        jdbc.execute("DELETE FROM booking_status_history");
        jdbc.execute("DELETE FROM payments");
        jdbc.execute("DELETE FROM bookings");
        jdbc.execute("DELETE FROM station_staff_assignments");
        jdbc.execute("DELETE FROM connectors");
        jdbc.execute("DELETE FROM charge_points");
        jdbc.execute("DELETE FROM stations");
        jdbc.execute("DELETE FROM user_profile");

        ownerAId = insertProfile("owner-a");
        ownerBId = insertProfile("owner-b");
        staffId = insertProfile("staff");
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
    void readOperationalBookingsScopesStrictlyToStation() {
        UUID bookingA1 = insertBooking(connectorA1Id, BookingStatus.CONFIRMED,
                NOW.plusSeconds(3600), NOW.plusSeconds(7200), null, NOW.plusSeconds(4500));
        UUID bookingA2 = insertBooking(connectorA2Id, BookingStatus.CHECKED_IN,
                NOW.plusSeconds(1800), NOW.plusSeconds(5400), null, NOW.plusSeconds(2700));
        UUID bookingB = insertBooking(connectorBId, BookingStatus.CONFIRMED,
                NOW.plusSeconds(3600), NOW.plusSeconds(7200), null, NOW.plusSeconds(4500));

        StationOperationalBookingFilter filter = new StationOperationalBookingFilter(null, null, null);
        var spec = predicateFactory.forStation(stationAId, filter);
        var page = readRepository.findAll(spec, PageRequest.of(0, 20));

        List<UUID> foundIds = page.getContent().stream().map(Booking::getId).toList();
        assertThat(foundIds).containsExactlyInAnyOrder(bookingA1, bookingA2);
        assertThat(foundIds).doesNotContain(bookingB);
    }

    @Test
    void filterByConnectorScopesOnlyToTargetConnector() {
        UUID bookingA1 = insertBooking(connectorA1Id, BookingStatus.CONFIRMED,
                NOW.plusSeconds(3600), NOW.plusSeconds(7200), null, NOW.plusSeconds(4500));
        UUID bookingA2 = insertBooking(connectorA2Id, BookingStatus.CONFIRMED,
                NOW.plusSeconds(3600), NOW.plusSeconds(7200), null, NOW.plusSeconds(4500));

        StationOperationalBookingFilter filter = new StationOperationalBookingFilter(connectorA1Id, null, null);
        var spec = predicateFactory.forStation(stationAId, filter);
        var page = readRepository.findAll(spec, PageRequest.of(0, 20));

        List<UUID> foundIds = page.getContent().stream().map(Booking::getId).toList();
        assertThat(foundIds).containsExactly(bookingA1);
    }

    @Test
    void timeWindowOverlapHalfOpenSemantics() {
        Instant windowFrom = NOW.plusSeconds(3600);
        Instant windowTo = NOW.plusSeconds(7200);

        // Abuts windowFrom: [NOW, windowFrom] -> endAt == windowFrom (not > windowFrom) -> excluded
        UUID bAbutLeft = insertBooking(connectorA1Id, BookingStatus.COMPLETED,
                NOW, windowFrom, null, null);

        // Abuts windowTo: [windowTo, windowTo + 3600] -> startAt == windowTo (not < windowTo) -> excluded
        UUID bAbutRight = insertBooking(connectorA1Id, BookingStatus.CONFIRMED,
                windowTo, windowTo.plusSeconds(3600), null, windowTo.plusSeconds(900));

        // Overlaps inside window: [windowFrom, windowTo] -> included
        UUID bInside = insertBooking(connectorA1Id, BookingStatus.CONFIRMED,
                windowFrom, windowTo, null, windowFrom.plusSeconds(900));

        StationOperationalBookingFilter filter = new StationOperationalBookingFilter(null, windowFrom, windowTo);
        var spec = predicateFactory.forStation(stationAId, filter);
        var page = readRepository.findAll(spec, PageRequest.of(0, 20));

        List<UUID> foundIds = page.getContent().stream().map(Booking::getId).toList();
        assertThat(foundIds).containsExactly(bInside);
        assertThat(foundIds).doesNotContain(bAbutLeft, bAbutRight);
    }

    @Test
    void findOperationalDetailReturnsBookingUnderStationAndRejectsForeignStation() {
        UUID bookingA = insertBooking(connectorA1Id, BookingStatus.CONFIRMED,
                NOW.plusSeconds(3600), NOW.plusSeconds(7200), null, NOW.plusSeconds(4500));

        var foundUnderA = readRepository.findOperationalDetail(bookingA, stationAId);
        assertThat(foundUnderA).isPresent();
        assertThat(foundUnderA.get().getBookingCode()).startsWith("BK-");
        assertThat(foundUnderA.get().getConnector().getChargePoint().getStation().getId()).isEqualTo(stationAId);

        var foundUnderB = readRepository.findOperationalDetail(bookingA, stationBId);
        assertThat(foundUnderB).isEmpty();
    }

    @Test
    void staffAssignmentLifeCycleStateInDatabase() {
        // Insert ACTIVE assignment
        UUID assignmentId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO station_staff_assignments(id, station_id, user_id, status, assigned_by, assigned_at, version)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?, 0)
                """,
                assignmentId, stationAId, staffId, ownerAId, Timestamp.from(NOW)
        );

        Integer activeCount = jdbc.queryForObject(
                "SELECT count(*) FROM station_staff_assignments WHERE station_id = ? AND user_id = ? AND status = 'ACTIVE'",
                Integer.class, stationAId, staffId
        );
        assertThat(activeCount).isEqualTo(1);

        // Revoke assignment
        jdbc.update("""
                UPDATE station_staff_assignments
                SET status = 'REVOKED', revoked_by = ?, revoked_at = ?
                WHERE id = ?
                """,
                ownerAId, Timestamp.from(NOW.plusSeconds(60)), assignmentId
        );

        Integer revokedActiveCount = jdbc.queryForObject(
                "SELECT count(*) FROM station_staff_assignments WHERE station_id = ? AND user_id = ? AND status = 'ACTIVE'",
                Integer.class, stationAId, staffId
        );
        assertThat(revokedActiveCount).isEqualTo(0);
    }

    @Test
    void effectiveStatusEvaluatedCorrectlyForOperationalDisplay() {
        // PENDING expired
        UUID pendingExpiredId = insertBooking(connectorA1Id, BookingStatus.PENDING,
                NOW.plusSeconds(3600), NOW.plusSeconds(7200), NOW.minusSeconds(10), null);

        // CONFIRMED no-show
        UUID confirmedNoShowId = insertBooking(connectorA2Id, BookingStatus.CONFIRMED,
                NOW.minusSeconds(1800), NOW.plusSeconds(1800), null, NOW.minusSeconds(900));

        // CONFIRMED active
        UUID confirmedActiveId = insertBooking(connectorA1Id, BookingStatus.CONFIRMED,
                NOW.plusSeconds(1800), NOW.plusSeconds(5400), null, NOW.plusSeconds(2700));

        Booking pendingExpired = readRepository.findById(pendingExpiredId).orElseThrow();
        Booking confirmedNoShow = readRepository.findById(confirmedNoShowId).orElseThrow();
        Booking confirmedActive = readRepository.findById(confirmedActiveId).orElseThrow();

        var evalPending = effectiveStatePolicy.evaluate(pendingExpired, NOW);
        assertThat(evalPending.effectiveStatus()).isEqualTo(BookingStatus.EXPIRED);

        var evalNoShow = effectiveStatePolicy.evaluate(confirmedNoShow, NOW);
        assertThat(evalNoShow.effectiveStatus()).isEqualTo(BookingStatus.CANCELLED);

        var evalActive = effectiveStatePolicy.evaluate(confirmedActive, NOW);
        assertThat(evalActive.effectiveStatus()).isEqualTo(BookingStatus.CONFIRMED);
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

    private UUID insertBooking(UUID connectorId, BookingStatus status,
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
}
