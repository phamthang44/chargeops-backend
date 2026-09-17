package com.thang.chargeops.booking;

import com.thang.chargeops.booking.dto.filter.DriverBookingHistoryFilter;
import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.booking.repository.specs.BookingSpecification;
import com.thang.chargeops.common.enums.BookingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class BookingRepositoryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-01T03:00:00Z");

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID connectorId;
    private UUID driverId;

    @BeforeEach
    void setUpData() {
        UUID ownerId = insertProfile("owner@chargeops.test");
        driverId = insertProfile("driver@chargeops.test");
        UUID stationId = insertStation(ownerId);
        UUID chargePointId = insertChargePoint(stationId);
        connectorId = insertConnector(chargePointId);
    }

    @Test
    void returnsOnlyBlockingHalfOpenOverlapsAndIgnoresExpiredPendingHold() {
        Instant rangeStart = Instant.parse("2026-09-02T03:00:00Z");
        Instant rangeEnd = Instant.parse("2026-09-02T05:00:00Z");

        insertBooking(
                BookingStatus.CONFIRMED,
                rangeStart.minusSeconds(1800),
                rangeStart.plusSeconds(1800),
                null
        );
        insertBooking(
                BookingStatus.PENDING,
                rangeStart.plusSeconds(3600),
                rangeStart.plusSeconds(5400),
                NOW.plusSeconds(600)
        );
        insertBooking(
                BookingStatus.PENDING,
                rangeStart.plusSeconds(1800),
                rangeStart.plusSeconds(2700),
                NOW.minusSeconds(1)
        );
        insertBooking(
                BookingStatus.CANCELLED,
                rangeStart.plusSeconds(900),
                rangeStart.plusSeconds(1800),
                null
        );
        insertBooking(
                BookingStatus.CONFIRMED,
                rangeStart.minusSeconds(3600),
                rangeStart,
                null
        );
        insertBooking(
                BookingStatus.CONFIRMED,
                rangeEnd,
                rangeEnd.plusSeconds(1800),
                null
        );

        var result = bookingRepository.findBlockingRanges(
                connectorId,
                rangeStart,
                rangeEnd,
                NOW,
                EnumSet.of(
                        BookingStatus.PENDING,
                        BookingStatus.CONFIRMED,
                        BookingStatus.CHECKED_IN,
                        BookingStatus.CHARGING
                )
        );

        assertThat(result)
                .extracting(range -> range.getStartAt() + "/" + range.getEndAt())
                .containsExactly(
                        rangeStart.minusSeconds(1800) + "/" + rangeStart.plusSeconds(1800),
                        rangeStart.plusSeconds(3600) + "/" + rangeStart.plusSeconds(5400)
                );
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"PENDING", "CONFIRMED", "CHECKED_IN", "CHARGING"})
    void activeBookingStatesBlockTheSameConnector(BookingStatus status) {
        Instant start = NOW.plusSeconds(86400);
        insertBooking(status, start, start.plusSeconds(3600), NOW.plusSeconds(600));
        assertThat(bookingRepository.existsOverlappingBooking(connectorId, start, start.plusSeconds(1800), NOW))
                .isTrue();
        assertThat(bookingRepository.existsOverlappingBooking(UUID.randomUUID(), start, start.plusSeconds(1800), NOW))
                .isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"COMPLETED", "EXPIRED", "CANCELLED"})
    void terminalBookingStatesDoNotBlock(BookingStatus status) {
        Instant start = NOW.plusSeconds(86400);
        insertBooking(status, start, start.plusSeconds(3600), null);
        assertThat(bookingRepository.existsOverlappingBooking(connectorId, start, start.plusSeconds(3600), NOW))
                .isFalse();
    }

    @Test
    void pendingStopsBlockingExactlyAtExpiryWithoutWaitingForScheduler() {
        Instant start = NOW.plusSeconds(86400);
        insertBooking(BookingStatus.PENDING, start, start.plusSeconds(3600), NOW.plusSeconds(600));
        assertThat(bookingRepository.existsOverlappingBooking(connectorId, start, start.plusSeconds(3600), NOW.plusSeconds(599)))
                .isTrue();
        assertThat(bookingRepository.existsOverlappingBooking(connectorId, start, start.plusSeconds(3600), NOW.plusSeconds(600)))
                .isFalse();
        assertThat(bookingRepository.existsOverlappingBooking(connectorId, start, start.plusSeconds(3600), NOW.plusSeconds(601)))
                .isFalse();
    }

    @Test
    void touchingEndpointsAreFreeButPartialAndContainingRangesOverlap() {
        Instant start = NOW.plusSeconds(86400);
        Instant end = start.plusSeconds(3600);
        insertBooking(BookingStatus.CONFIRMED, start, end, null);
        assertThat(bookingRepository.existsOverlappingBooking(connectorId, start.minusSeconds(1800), start, NOW)).isFalse();
        assertThat(bookingRepository.existsOverlappingBooking(connectorId, end, end.plusSeconds(1800), NOW)).isFalse();
        assertThat(bookingRepository.existsOverlappingBooking(connectorId, start.minusSeconds(1800), start.plusSeconds(1800), NOW)).isTrue();
        assertThat(bookingRepository.existsOverlappingBooking(connectorId, end.minusSeconds(1800), end.plusSeconds(1800), NOW)).isTrue();
        assertThat(bookingRepository.existsOverlappingBooking(connectorId, start.minusSeconds(1800), end.plusSeconds(1800), NOW)).isTrue();
    }

    @Test
    void overlapAcrossLocalMidnightUsesTheFullRequestedRange() {
        Instant start = Instant.parse("2026-09-02T16:30:00Z"); // 23:30 in Vietnam
        insertBooking(BookingStatus.CONFIRMED, start, start.plusSeconds(7200), null);
        assertThat(bookingRepository.existsOverlappingBooking(connectorId, start.plusSeconds(1800), start.plusSeconds(3600), NOW))
                .isTrue();
    }

    @Test
    void activeDriverPageUsesEffectiveStatusBoundaries() {
        Instant firstStart = NOW.plusSeconds(3_600);
        UUID checkedIn = insertBooking(
                BookingStatus.CHECKED_IN,
                firstStart,
                firstStart.plusSeconds(3_600),
                null,
                null
        );
        UUID pending = insertBooking(
                BookingStatus.PENDING,
                firstStart.plusSeconds(3_600),
                firstStart.plusSeconds(7_200),
                NOW.plusSeconds(1),
                null
        );
        UUID confirmed = insertBooking(
                BookingStatus.CONFIRMED,
                firstStart.plusSeconds(7_200),
                firstStart.plusSeconds(10_800),
                null,
                NOW.plusSeconds(1)
        );
        UUID charging = insertBooking(
                BookingStatus.CHARGING,
                firstStart.plusSeconds(10_800),
                firstStart.plusSeconds(14_400),
                null,
                null
        );

        insertBooking(
                BookingStatus.PENDING,
                firstStart.plusSeconds(14_400),
                firstStart.plusSeconds(18_000),
                NOW,
                null
        );
        insertBooking(
                BookingStatus.CONFIRMED,
                firstStart.plusSeconds(18_000),
                firstStart.plusSeconds(21_600),
                null,
                NOW
        );
        insertBooking(
                BookingStatus.COMPLETED,
                firstStart.plusSeconds(21_600),
                firstStart.plusSeconds(25_200),
                null,
                null
        );

        var result = bookingRepository.findActiveForDriver(
                driverId,
                NOW,
                PageRequest.of(0, 20)
        );

        assertThat(result.getContent())
                .extracting(booking -> booking.getId())
                .containsExactly(checkedIn, pending, confirmed, charging);
    }

    @Test
    void historySpecificationPartitionsEffectiveStatusesAndScopesDriver() {
        Instant firstStart = NOW.minusSeconds(25_200);
        UUID completed = insertBooking(
                BookingStatus.COMPLETED,
                firstStart,
                firstStart.plusSeconds(3_600),
                null,
                null
        );
        UUID cancelled = insertBooking(
                BookingStatus.CANCELLED,
                firstStart.plusSeconds(3_600),
                firstStart.plusSeconds(7_200),
                null,
                null
        );
        UUID expired = insertBooking(
                BookingStatus.EXPIRED,
                firstStart.plusSeconds(7_200),
                firstStart.plusSeconds(10_800),
                null,
                null
        );
        UUID stalePending = insertBooking(
                BookingStatus.PENDING,
                firstStart.plusSeconds(10_800),
                firstStart.plusSeconds(14_400),
                NOW,
                null
        );
        UUID staleConfirmed = insertBooking(
                BookingStatus.CONFIRMED,
                firstStart.plusSeconds(14_400),
                firstStart.plusSeconds(18_000),
                null,
                NOW
        );
        insertBooking(
                BookingStatus.PENDING,
                firstStart.plusSeconds(18_000),
                firstStart.plusSeconds(21_600),
                NOW.plusSeconds(1),
                null
        );
        insertBooking(
                BookingStatus.CONFIRMED,
                firstStart.plusSeconds(21_600),
                firstStart.plusSeconds(25_200),
                null,
                NOW.plusSeconds(1)
        );

        UUID otherDriver = insertProfile("other-driver@chargeops.test");
        UUID otherBooking = insertBooking(
                BookingStatus.COMPLETED,
                firstStart.plusSeconds(25_200),
                firstStart.plusSeconds(28_800),
                null,
                null
        );
        jdbcTemplate.update(
                "UPDATE bookings SET driver_id = ? WHERE id = ?",
                otherDriver,
                otherBooking
        );

        var all = bookingRepository.findAll(
                BookingSpecification.historyForDriver(
                        driverId,
                        new DriverBookingHistoryFilter(
                                "",
                                DriverBookingHistoryFilter.HistoryStatus.ALL
                        ),
                        NOW
                ),
                PageRequest.of(
                        0,
                        20,
                        Sort.by(
                                Sort.Order.desc("startAt"),
                                Sort.Order.desc("id")
                        )
                )
        );

        assertThat(all.getContent())
                .extracting(Booking::getId)
                .containsExactly(
                        staleConfirmed,
                        stalePending,
                        expired,
                        cancelled,
                        completed
                );

        long completedCount = bookingRepository.count(
                BookingSpecification.historyForDriver(
                        driverId,
                        new DriverBookingHistoryFilter(
                                "",
                                DriverBookingHistoryFilter.HistoryStatus.COMPLETED
                        ),
                        NOW
                )
        );
        long cancelledCount = bookingRepository.count(
                BookingSpecification.historyForDriver(
                        driverId,
                        new DriverBookingHistoryFilter(
                                "",
                                DriverBookingHistoryFilter.HistoryStatus.CANCELLED
                        ),
                        NOW
                )
        );

        assertThat(completedCount).isOne();
        assertThat(cancelledCount).isEqualTo(4);
    }

    @Test
    void historySpecificationSearchesImmutableSnapshotsAndEscapesWildcards() {
        UUID matching = insertBooking(
                BookingStatus.COMPLETED,
                NOW.minusSeconds(7_200),
                NOW.minusSeconds(3_600),
                null,
                null
        );
        UUID other = insertBooking(
                BookingStatus.CANCELLED,
                NOW.minusSeconds(14_400),
                NOW.minusSeconds(10_800),
                null,
                null
        );
        jdbcTemplate.update(
                """
                UPDATE bookings
                SET booking_code = 'BK-ALPHA-01',
                    station_name_snapshot = 'Alpha Charging Hub'
                WHERE id = ?
                """,
                matching
        );
        jdbcTemplate.update(
                "UPDATE bookings SET booking_code = 'BK-BETA-01' WHERE id = ?",
                other
        );

        var byName = bookingRepository.findAll(
                BookingSpecification.historyForDriver(
                        driverId,
                        new DriverBookingHistoryFilter(
                                " alpha ",
                                DriverBookingHistoryFilter.HistoryStatus.ALL
                        ),
                        NOW
                ),
                PageRequest.of(0, 20)
        );
        long wildcardOnly = bookingRepository.count(
                BookingSpecification.historyForDriver(
                        driverId,
                        new DriverBookingHistoryFilter(
                                "%",
                                DriverBookingHistoryFilter.HistoryStatus.ALL
                        ),
                        NOW
                )
        );

        assertThat(byName.getContent())
                .extracting(Booking::getId)
                .containsExactly(matching);
        assertThat(wildcardOnly).isZero();
    }

    private UUID insertProfile(String email) {
        UUID profileId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO user_profile
                    (id, keycloak_id, email, status, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?)
                """,
                profileId,
                UUID.randomUUID().toString(),
                email,
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        );
        return profileId;
    }

    private UUID insertStation(UUID ownerId) {
        jdbcTemplate.update(
                "INSERT INTO provinces (code, name, full_name) VALUES ('79', 'HCM', 'Ho Chi Minh')"
        );
        jdbcTemplate.update(
                "INSERT INTO wards (code, name, full_name, province_code) VALUES ('26734', 'Ben Nghe', 'Ben Nghe', '79')"
        );
        UUID stationId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO stations
                    (id, station_code, owner_id, name, address_line, ward_code,
                     latitude, longitude, contact_phone, planned_charge_point_count,
                     status, version, created_at, updated_at)
                VALUES (?, 'ST-BOOKING-01', ?, 'Station', '1 Le Loi', '26734',
                        ?, ?, '0900000000', 1, 'ACTIVE', 0, ?, ?)
                """,
                stationId,
                ownerId,
                new BigDecimal("10.000000"),
                new BigDecimal("106.000000"),
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        );
        return stationId;
    }

    private UUID insertChargePoint(UUID stationId) {
        UUID chargePointId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO charge_points
                    (id, station_id, charge_point_code, max_power_kw,
                     provisioning_status, operational_status, version,
                     created_at, updated_at)
                VALUES (?, ?, 'CP-01', ?, 'ACTIVE', 'AVAILABLE', 0, ?, ?)
                """,
                chargePointId,
                stationId,
                new BigDecimal("120.00"),
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        );
        return chargePointId;
    }

    private UUID insertConnector(UUID chargePointId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO connectors
                    (id, charge_point_id, connector_code, connector_type,
                     power_kw, charger_type, runtime_status, version,
                     created_at, updated_at)
                VALUES (?, ?, 'C-01', 'CCS2', ?, 'DC', 'AVAILABLE', 0, ?, ?)
                """,
                id,
                chargePointId,
                new BigDecimal("120.00"),
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        );
        return id;
    }

    private UUID insertBooking(
            BookingStatus status,
            Instant startAt,
            Instant endAt,
            Instant expiresAt
    ) {
        return insertBooking(status, startAt, endAt, expiresAt, null);
    }

    private UUID insertBooking(
            BookingStatus status,
            Instant startAt,
            Instant endAt,
            Instant expiresAt,
            Instant checkInDeadline
    ) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO bookings
                    (id, driver_id, connector_id, start_at, end_at, status,
                     total_amount, station_name_snapshot, station_address_snapshot,
                     charge_point_code_snapshot, connector_code_snapshot,
                     expires_at, check_in_deadline, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'Station', '1 Le Loi',
                        'CP-01', 'C-01', ?, ?, 0, ?, ?)
                """,
                bookingId,
                driverId,
                connectorId,
                Timestamp.from(startAt),
                Timestamp.from(endAt),
                status.name(),
                new BigDecimal("100000.00"),
                expiresAt == null ? null : Timestamp.from(expiresAt),
                checkInDeadline == null
                        ? null
                        : Timestamp.from(checkInDeadline),
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        );
        return bookingId;
    }
}
