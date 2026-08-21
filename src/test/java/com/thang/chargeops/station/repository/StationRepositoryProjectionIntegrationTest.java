package com.thang.chargeops.station.repository;

import com.thang.chargeops.common.enums.Plan;
import com.thang.chargeops.common.enums.StationStatus;
import com.thang.chargeops.station.projection.OwnerStationSummaryProjection;
import com.thang.chargeops.station.projection.StationApprovalSummaryProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class StationRepositoryProjectionIntegrationTest {

    @Autowired
    private StationRepository stationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID ownerId;
    private UUID stationId;
    private Instant submittedAt;

    @BeforeEach
    void setUpData() {
        ownerId = UUID.randomUUID();
        stationId = UUID.randomUUID();
        submittedAt = Instant.parse("2026-08-14T08:30:00Z");

        jdbcTemplate.update(
                "INSERT INTO provinces (code, name, full_name) VALUES (?, ?, ?)",
                "01", "Ha Noi", "Thanh pho Ha Noi"
        );
        jdbcTemplate.update(
                "INSERT INTO wards (code, name, full_name, province_code) VALUES (?, ?, ?, ?)",
                "00001", "Cau Giay", "Phuong Cau Giay", "01"
        );
        jdbcTemplate.update(
                """
                        INSERT INTO user_profile
                            (id, keycloak_id, email, display_name, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                ownerId,
                UUID.randomUUID().toString(),
                "owner@chargeops.test",
                "",
                "ACTIVE",
                Timestamp.from(submittedAt),
                Timestamp.from(submittedAt)
        );
        jdbcTemplate.update(
                """
                        INSERT INTO stations
                            (id, station_code, owner_id, name, address_line, ward_code,
                             latitude, longitude, contact_phone, planned_charge_point_count,
                             status, version, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                stationId,
                "ST-0001",
                ownerId,
                "Station One",
                "123 Main Street",
                "00001",
                new BigDecimal("21.027763"),
                new BigDecimal("105.834160"),
                "0912345678",
                3,
                "PENDING_APPROVAL",
                0L,
                Timestamp.from(submittedAt),
                Timestamp.from(submittedAt)
        );
    }

    @Test
    void returnsOwnerSummaryWithoutLoadingStationEntityGraph() {
        Page<OwnerStationSummaryProjection> result = stationRepository.findOwnerStationSummaries(
                ownerId,
                Instant.now(),
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"))
        );

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).singleElement().satisfies(summary -> {
            assertThat(summary.getStationCode()).isEqualTo("ST-0001");
            assertThat(summary.getName()).isEqualTo("Station One");
            assertThat(summary.getAddressLine()).isEqualTo("123 Main Street");
            assertThat(summary.getProvinceName()).isEqualTo("Thanh pho Ha Noi");
            assertThat(summary.getWardName()).isEqualTo("Phuong Cau Giay");
            assertThat(summary.getPlannedChargePointCount()).isEqualTo(3);
            assertThat(summary.getStatus()).isEqualTo(StationStatus.PENDING_APPROVAL);
            assertThat(summary.getLicensePlan()).isNull();
            assertThat(summary.getLicenseExpiresAt()).isNull();
        });
    }

    @Test
    void returnsCurrentlyActiveLicenseSummary() {
        Instant at = Instant.parse("2026-08-15T08:30:00Z");
        Instant expiresAt = Instant.parse("2027-08-15T08:30:00Z");
        jdbcTemplate.update(
                """
                        INSERT INTO licenses
                            (id, station_id, owner_id, plan, fee_amount, start_at, expires_at,
                             status, license_code, version, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(),
                stationId,
                ownerId,
                "YEARLY",
                new BigDecimal("3600000.00"),
                Timestamp.from(at.minusSeconds(60)),
                Timestamp.from(expiresAt),
                "ACTIVE",
                "LIC-TEST-0001",
                0L,
                Timestamp.from(submittedAt),
                Timestamp.from(submittedAt)
        );

        Page<OwnerStationSummaryProjection> result = stationRepository.findOwnerStationSummaries(
                ownerId,
                at,
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"))
        );

        assertThat(result.getContent()).singleElement().satisfies(summary -> {
            assertThat(summary.getLicensePlan()).isEqualTo(Plan.YEARLY);
            assertThat(summary.getLicenseExpiresAt()).isEqualTo(expiresAt);
        });
    }

    @Test
    void returnsApprovalSummaryAndFallsBackToOwnerEmail() {
        Page<StationApprovalSummaryProjection> result = stationRepository.findStationApprovalSummaries(
                StationStatus.PENDING_APPROVAL,
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"))
        );

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).singleElement().satisfies(summary -> {
            assertThat(summary.getStationCode()).isEqualTo("ST-0001");
            assertThat(summary.getOwnerDisplayName()).isEqualTo("owner@chargeops.test");
            assertThat(summary.getProvinceName()).isEqualTo("Thanh pho Ha Noi");
            assertThat(summary.getPlannedChargePointCount()).isEqualTo(3);
            assertThat(summary.getSubmittedAt()).isEqualTo(submittedAt);
        });
    }

    @Test
    void detectsConcurrentStationUpdateByVersion() {
        var staleStation = stationRepository.findById(stationId).orElseThrow();

        jdbcTemplate.update(
                "UPDATE stations SET status = ?, version = version + 1 WHERE id = ?",
                "ACTIVE",
                stationId
        );
        staleStation.setStatus(StationStatus.REJECTED);

        assertThatThrownBy(stationRepository::flush)
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void softDeletesStationUsingItsCurrentVersion() {
        var station = stationRepository.findById(stationId).orElseThrow();

        stationRepository.delete(station);
        stationRepository.flush();

        Integer deletedRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stations WHERE id = ? AND deleted_at IS NOT NULL AND version = 1",
                Integer.class,
                stationId
        );
        assertThat(deletedRows).isOne();
    }
}
