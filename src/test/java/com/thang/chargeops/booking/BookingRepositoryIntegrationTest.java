package com.thang.chargeops.booking;

import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.common.enums.BookingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
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

    private void insertBooking(
            BookingStatus status,
            Instant startAt,
            Instant endAt,
            Instant expiresAt
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO bookings
                    (id, driver_id, connector_id, start_at, end_at, status,
                     total_amount, station_name_snapshot, station_address_snapshot,
                     charge_point_code_snapshot, connector_code_snapshot,
                     expires_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'Station', '1 Le Loi',
                        'CP-01', 'C-01', ?, ?, ?)
                """,
                UUID.randomUUID(),
                driverId,
                connectorId,
                Timestamp.from(startAt),
                Timestamp.from(endAt),
                status.name(),
                new BigDecimal("100000.00"),
                expiresAt == null ? null : Timestamp.from(expiresAt),
                Timestamp.from(NOW),
                Timestamp.from(NOW)
        );
    }
}
