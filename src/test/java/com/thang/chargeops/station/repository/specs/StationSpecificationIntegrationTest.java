package com.thang.chargeops.station.repository.specs;

import com.thang.chargeops.station.dto.station.filter.StationFilter;
import com.thang.chargeops.station.repository.StationRepository;
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
class StationSpecificationIntegrationTest {

    @Autowired
    private StationRepository stationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUpData() {
        insertProvinceAndWard("01", "00001", "Ha Noi", "Cau Giay");
        insertProvinceAndWard("79", "26734", "Ho Chi Minh", "Ben Nghe");

        UUID aliceId = insertOwner("alice@chargeops.test", "Alice Energy");
        UUID bobId = insertOwner("bob@chargeops.test", "Bob Mobility");

        insertStation(
                aliceId,
                "ST-HN-001",
                "Central Charging Hub",
                "12 Tran Duy Hung",
                "00001",
                "ACTIVE"
        );
        insertStation(
                bobId,
                "ST-HCM-002",
                "Riverside Station",
                "88 Ton Duc Thang",
                "26734",
                "SUSPENDED"
        );
    }

    @Test
    void searchMatchesStationCodeNameAddressAndOwner() {
        assertSingleStation("st-hn", "ST-HN-001");
        assertSingleStation("central", "ST-HN-001");
        assertSingleStation("tran duy hung", "ST-HN-001");
        assertSingleStation("alice energy", "ST-HN-001");
        assertSingleStation("BOB@CHARGEOPS.TEST", "ST-HCM-002");
    }

    private void assertSingleStation(String search, String expectedStationCode) {
        StationFilter filter = new StationFilter();
        filter.setSearch(search);

        var result = stationRepository.findAll(StationSpecification.filter(filter));

        assertThat(result)
                .singleElement()
                .satisfies(station -> assertThat(station.getStationCode())
                        .isEqualTo(expectedStationCode));
    }

    private void insertProvinceAndWard(
            String provinceCode,
            String wardCode,
            String provinceName,
            String wardName
    ) {
        jdbcTemplate.update(
                "INSERT INTO provinces (code, name, full_name) VALUES (?, ?, ?)",
                provinceCode,
                provinceName,
                provinceName
        );
        jdbcTemplate.update(
                "INSERT INTO wards (code, name, full_name, province_code) VALUES (?, ?, ?, ?)",
                wardCode,
                wardName,
                wardName,
                provinceCode
        );
    }

    private UUID insertOwner(String email, String displayName) {
        UUID ownerId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-21T00:00:00Z");
        jdbcTemplate.update(
                """
                        INSERT INTO user_profile
                            (id, keycloak_id, email, display_name, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                ownerId,
                UUID.randomUUID().toString(),
                email,
                displayName,
                "ACTIVE",
                Timestamp.from(now),
                Timestamp.from(now)
        );
        return ownerId;
    }

    private void insertStation(
            UUID ownerId,
            String stationCode,
            String name,
            String address,
            String wardCode,
            String status
    ) {
        Instant now = Instant.parse("2026-08-21T00:00:00Z");
        jdbcTemplate.update(
                """
                        INSERT INTO stations
                            (id, station_code, owner_id, name, address_line, ward_code,
                             latitude, longitude, contact_phone, planned_charge_point_count,
                             status, version, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(),
                stationCode,
                ownerId,
                name,
                address,
                wardCode,
                new BigDecimal("10.000000"),
                new BigDecimal("106.000000"),
                "0900000000",
                2,
                status,
                0L,
                Timestamp.from(now),
                Timestamp.from(now)
        );
    }
}
