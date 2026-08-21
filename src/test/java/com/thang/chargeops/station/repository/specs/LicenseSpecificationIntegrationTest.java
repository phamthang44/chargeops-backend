package com.thang.chargeops.station.repository.specs;

import com.thang.chargeops.common.enums.LicenseStatus;
import com.thang.chargeops.station.dto.license.filter.LicenseFilter;
import com.thang.chargeops.station.repository.LicenseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class LicenseSpecificationIntegrationTest {

    private static final Instant CREATED_AT =
            Instant.parse("2026-08-18T08:00:00Z");

    @Autowired
    private LicenseRepository licenseRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID centralStationId;

    @BeforeEach
    void setUpData() {
        jdbcTemplate.update(
                "INSERT INTO provinces (code, name, full_name) VALUES (?, ?, ?)",
                "01", "Ha Noi", "Thanh pho Ha Noi"
        );
        jdbcTemplate.update(
                """
                        INSERT INTO wards (code, name, full_name, province_code)
                        VALUES (?, ?, ?, ?)
                        """,
                "00001", "Cau Giay", "Phuong Cau Giay", "01"
        );

        UUID aliceId = insertOwner(
                "alice@chargeops.test",
                "Alice Operator"
        );
        UUID bobId = insertOwner(
                "bob@chargeops.test",
                "Bob Manager"
        );

        centralStationId = insertStation(
                aliceId,
                "ST-0001",
                "Central Charging Hub"
        );
        UUID airportStationId = insertStation(
                bobId,
                "ST-0002",
                "Airport Charging Hub"
        );

        insertLicense(
                centralStationId,
                aliceId,
                "LIC-001234",
                LicenseStatus.ACTIVE
        );
        insertLicense(
                airportStationId,
                bobId,
                "LIC-101234",
                LicenseStatus.SUSPENDED
        );
    }

    @Test
    void returnsAllLicensesWhenFilterIsNull() {
        var result = licenseRepository.findAll(
                LicenseSpecification.filter(null)
        );

        assertThat(result).hasSize(2);
    }

    @Test
    void returnsAllLicensesWhenNoFilterContributesAPredicate() {
        LicenseFilter emptyFilter = new LicenseFilter(
                null, null, null, null, null, null, null, null
        );

        var result = licenseRepository.findAll(
                LicenseSpecification.filter(emptyFilter)
        );

        assertThat(result).hasSize(2);
    }

    @Test
    void matchesLicenseCodeByPrefixIgnoringCase() {
        var prefixResult = licenseRepository.findAll(
                LicenseSpecification.filter(filterWithLicenseCode("lic-00"))
        );
        var middleResult = licenseRepository.findAll(
                LicenseSpecification.filter(filterWithLicenseCode("001234"))
        );

        assertThat(prefixResult)
                .singleElement()
                .satisfies(license ->
                        assertThat(license.getLicenseCode())
                                .isEqualTo("LIC-001234"));
        assertThat(middleResult).isEmpty();
    }

    @Test
    void globalSearchAlsoMatchesLicenseCodeByPrefix() {
        LicenseFilter prefixFilter = new LicenseFilter(
                "lic-00", null, null, null, null, null, null, null
        );
        LicenseFilter middleFilter = new LicenseFilter(
                "001234", null, null, null, null, null, null, null
        );

        var prefixResult = licenseRepository.findAll(
                LicenseSpecification.filter(prefixFilter)
        );
        var middleResult = licenseRepository.findAll(
                LicenseSpecification.filter(middleFilter)
        );

        assertThat(prefixResult)
                .singleElement()
                .satisfies(license ->
                        assertThat(license.getLicenseCode())
                                .isEqualTo("LIC-001234"));
        assertThat(middleResult).isEmpty();
    }

    @Test
    void filtersOwnerNameByContainsAndEmailByExactMatch() {
        LicenseFilter filter = new LicenseFilter(
                null,
                null,
                null,
                "operator",
                "ALICE@CHARGEOPS.TEST",
                null,
                null,
                null
        );

        var result = licenseRepository.findAll(
                LicenseSpecification.filter(filter)
        );

        assertThat(result)
                .singleElement()
                .satisfies(license ->
                        assertThat(license.getLicenseCode())
                                .isEqualTo("LIC-001234"));
    }

    @Test
    void combinesGlobalSearchAndSpecificFiltersWithAnd() {
        LicenseFilter filter = new LicenseFilter(
                "central",
                null,
                LicenseStatus.ACTIVE,
                null,
                null,
                null,
                "st-00",
                centralStationId
        );

        var result = licenseRepository.findAll(
                LicenseSpecification.filter(filter)
        );

        assertThat(result)
                .singleElement()
                .satisfies(license ->
                        assertThat(license.getLicenseCode())
                                .isEqualTo("LIC-001234"));
    }

    @Test
    void treatsLikeWildcardsAsLiteralCharacters() {
        LicenseFilter filter = new LicenseFilter(
                null,
                null,
                null,
                "%",
                null,
                null,
                null,
                null
        );

        var result = licenseRepository.findAll(
                LicenseSpecification.filter(filter)
        );

        assertThat(result).isEmpty();
    }

    private LicenseFilter filterWithLicenseCode(String licenseCode) {
        return new LicenseFilter(
                null,
                licenseCode,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private UUID insertOwner(String email, String displayName) {
        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                        INSERT INTO user_profile
                            (id, keycloak_id, email, display_name, status,
                             created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                ownerId,
                UUID.randomUUID().toString(),
                email,
                displayName,
                "ACTIVE",
                Timestamp.from(CREATED_AT),
                Timestamp.from(CREATED_AT)
        );
        return ownerId;
    }

    private UUID insertStation(
            UUID ownerId,
            String stationCode,
            String stationName) {
        UUID stationId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                        INSERT INTO stations
                            (id, station_code, owner_id, name, address_line,
                             ward_code, latitude, longitude, contact_phone,
                             planned_charge_point_count, status, version,
                             created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                stationId,
                stationCode,
                ownerId,
                stationName,
                "123 Main Street",
                "00001",
                new BigDecimal("21.027763"),
                new BigDecimal("105.834160"),
                "0912345678",
                3,
                "ACTIVE",
                0L,
                Timestamp.from(CREATED_AT),
                Timestamp.from(CREATED_AT)
        );
        return stationId;
    }

    private void insertLicense(
            UUID stationId,
            UUID ownerId,
            String licenseCode,
            LicenseStatus status) {
        jdbcTemplate.update(
                """
                        INSERT INTO licenses
                            (id, station_id, owner_id, plan, fee_amount,
                             start_at, expires_at, status, license_code,
                             version, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(),
                stationId,
                ownerId,
                "YEARLY",
                new BigDecimal("3600000.00"),
                Timestamp.from(CREATED_AT),
                Timestamp.from(CREATED_AT.plusSeconds(31_536_000)),
                status.name(),
                licenseCode,
                0L,
                Timestamp.from(CREATED_AT),
                Timestamp.from(CREATED_AT)
        );
    }
}
