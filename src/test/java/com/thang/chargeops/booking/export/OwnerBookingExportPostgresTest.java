package com.thang.chargeops.booking.export;

import com.thang.chargeops.booking.dto.filter.BookingWindowMode;
import com.thang.chargeops.booking.dto.filter.OwnerBookingFilter;
import com.thang.chargeops.booking.mapper.OwnerBookingMapper;
import com.thang.chargeops.booking.policy.BookingEffectiveStatePolicy;
import com.thang.chargeops.booking.repository.specs.OwnerBookingPredicateFactory;
import com.thang.chargeops.booking.support.OwnerResourceScopeValidator;
import com.thang.chargeops.common.enums.BookingStatus;
import com.thang.chargeops.exception.AppException;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayInputStream;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true", "spring.docker.compose.enabled=false"})
@AutoConfigureTestDatabase(replace = NONE)
@Import({OwnerBookingExportService.class, OwnerBookingWorkbookWriter.class, OwnerBookingPredicateFactory.class,
        OwnerResourceScopeValidator.class, OwnerBookingExportPostgresTest.Beans.class})
class OwnerBookingExportPostgresTest {
    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine").asCompatibleSubstituteFor("postgres"));
    @Autowired JdbcTemplate jdbc;
    @Autowired OwnerBookingExportService service;
    @MockitoBean CurrentProfileProvider profile;
    private UUID ownerA, ownerB, driver, stationA, stationB, connectorA, connectorB;

    @TestConfiguration static class Beans {
        @Bean Clock applicationClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean OwnerBookingMapper ownerBookingMapper() { return new OwnerBookingMapper(new BookingEffectiveStatePolicy(), null); }
    }

    @BeforeEach void data() {
        ownerA = profile(); ownerB = profile(); driver = profile();
        stationA = station(ownerA); stationB = station(ownerB);
        connectorA = connector(stationA); connectorB = connector(stationB);
        when(profile.requireProfileId()).thenReturn(ownerA);
    }

    @Test void ownerScopedExportIncludesAllPagesInStableOrderAndRejectsForeignFilters() throws Exception {
        for (int i = 0; i < 25; i++) booking(connectorA, "OWN-A-" + String.format("%02d", i), "COMPLETED", NOW.plusSeconds(i * 3600L), null, null);
        booking(connectorB, "FOREIGN-B", "COMPLETED", NOW, null, null);
        var filter = new OwnerBookingFilter(null, null, null, null, null);
        try (var workbook = export(filter, "startAt,asc")) {
            var sheet = workbook.getSheet("Bookings");
            assertThat(sheet.getLastRowNum()).isEqualTo(25);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("OWN-A-00");
            assertThat(sheet.getRow(25).getCell(0).getStringCellValue()).isEqualTo("OWN-A-24");
            assertThat(workbook.getSheet("Summary").getRow(10).getCell(1).getNumericCellValue()).isEqualTo(25);
        }
        assertThatThrownBy(() -> service.export(new OwnerBookingFilter(stationB, null, null, null, null), "startAt,desc", Locale.ENGLISH))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getHttpStatus().value()).isEqualTo(403));
        assertThatThrownBy(() -> service.export(new OwnerBookingFilter(null, connectorB, null, null, null), "startAt,desc", Locale.ENGLISH))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getHttpStatus().value()).isEqualTo(403));
    }

    @Test void expiryAndNoShowUseSameEvaluationForQueryRowsAndSummaryWithoutWriting() throws Exception {
        booking(connectorA, "EXPIRES-AT-NOW", "PENDING", NOW.plusSeconds(7200), NOW, null);
        booking(connectorA, "STILL-PENDING", "PENDING", NOW.plusSeconds(14400), NOW.plusSeconds(1), null);
        booking(connectorA, "NO-SHOW-NOW", "CONFIRMED", NOW.minusSeconds(3600), null, NOW);
        try (var workbook = export(new OwnerBookingFilter(null, null, null, null, BookingStatus.EXPIRED), "startAt,desc")) {
            assertThat(workbook.getSheet("Bookings").getLastRowNum()).isEqualTo(1);
            assertThat(workbook.getSheet("Bookings").getRow(1).getCell(7).getStringCellValue()).isEqualTo("EXPIRED");
            assertThat(workbook.getSheet("Summary").getRow(10).getCell(1).getNumericCellValue()).isEqualTo(1);
        }
        try (var workbook = export(new OwnerBookingFilter(null, null, null, null, BookingStatus.CANCELLED), "startAt,desc")) {
            assertThat(workbook.getSheet("Bookings").getLastRowNum()).isEqualTo(1);
            assertThat(workbook.getSheet("Bookings").getRow(1).getCell(8).getStringCellValue()).isEqualTo("NO_SHOW");
        }
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE booking_code='EXPIRES-AT-NOW'", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE booking_code='NO-SHOW-NOW'", String.class)).isEqualTo("CONFIRMED");
    }

    @Test void halfOpenWindowAndConnectorFilterMatchListSemantics() throws Exception {
        booking(connectorA, "ABUTS-LEFT", "COMPLETED", NOW.minusSeconds(3600), null, null);
        booking(connectorA, "IN-WINDOW", "COMPLETED", NOW, null, null);
        booking(connectorA, "ABUTS-RIGHT", "COMPLETED", NOW.plusSeconds(3600), null, null);
        var filter = new OwnerBookingFilter(stationA, connectorA, NOW, NOW.plusSeconds(3600), BookingStatus.COMPLETED, BookingWindowMode.OVERLAP);
        try (var workbook = export(filter, "startAt,desc")) {
            assertThat(workbook.getSheet("Bookings").getLastRowNum()).isEqualTo(1);
            assertThat(workbook.getSheet("Bookings").getRow(1).getCell(0).getStringCellValue()).isEqualTo("IN-WINDOW");
        }
    }

    private XSSFWorkbook export(OwnerBookingFilter filter, String sort) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(service.export(filter, sort, Locale.ENGLISH).content()));
    }
    private UUID profile() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_profile(id,keycloak_id,email) VALUES (?,?,?)", id, id.toString(), id + "@chargeops.test");
        return id;
    }
    private UUID station(UUID owner) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO stations(id,owner_id,name,address_line,contact_phone,status) VALUES (?,?,'Trạm Thủ Đức','Test','0123456789','ACTIVE')", id, owner);
        return id;
    }
    private UUID connector(UUID station) {
        UUID cp = UUID.randomUUID(), connector = UUID.randomUUID();
        jdbc.update("INSERT INTO charge_points(id,station_id,charge_point_code,provisioning_status,operational_status) VALUES (?,?,?,'ACTIVE','AVAILABLE')", cp, station, "CP-" + cp);
        jdbc.update("INSERT INTO connectors(id,charge_point_id,connector_code,connector_type,power_kw,charger_type,runtime_status) VALUES (?,?,?,'CCS2',60,'DC','AVAILABLE')", connector, cp, "CON-" + connector);
        return connector;
    }
    private void booking(UUID connector, String code, String status, Instant start, Instant expires, Instant deadline) {
        jdbc.update("""
                INSERT INTO bookings(id,driver_id,connector_id,booking_code,start_at,end_at,status,total_amount,
                station_name_snapshot,station_address_snapshot,charge_point_code_snapshot,connector_code_snapshot,
                expires_at,policy_version,payment_confirmed_at,free_cancellation_deadline,check_in_deadline,version)
                VALUES (?,?,?,?,?,?,?,126000,'Trạm Thủ Đức','Test','CP','CON',?,'booking-v4.9',?,?,?,0)
                """, UUID.randomUUID(), driver, connector, code, Timestamp.from(start), Timestamp.from(start.plusSeconds(3600)), status,
                expires == null ? null : Timestamp.from(expires), Timestamp.from(NOW), Timestamp.from(start.minusSeconds(1800)),
                deadline == null ? null : Timestamp.from(deadline));
    }
}
