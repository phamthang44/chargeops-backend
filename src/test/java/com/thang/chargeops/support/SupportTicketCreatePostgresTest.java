package com.thang.chargeops.support;

import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.profile.support.CurrentProfileProvider;
import com.thang.chargeops.support.dto.request.CreateTicketRequest;
import com.thang.chargeops.support.model.TicketCategory;
import com.thang.chargeops.support.model.TicketPriority;
import com.thang.chargeops.support.repository.SupportTicketRepository;
import com.thang.chargeops.support.repository.TicketMessageRepository;
import com.thang.chargeops.support.service.SupportTicketService;
import com.thang.chargeops.support.service.impl.SupportTicketServiceImpl;
import jakarta.persistence.EntityManager;
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.docker.compose.enabled=false"
})
@AutoConfigureTestDatabase(replace = NONE)
@Import({SupportTicketServiceImpl.class, SupportTicketCreatePostgresTest.FixedClockConfig.class})
class SupportTicketCreatePostgresTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                    .asCompatibleSubstituteFor("postgres"));

    @TestConfiguration
    static class FixedClockConfig {
        @Bean Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-30T02:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired SupportTicketService ticketService;
    @Autowired SupportTicketRepository tickets;
    @Autowired TicketMessageRepository messages;
    @Autowired BookingRepository bookings;
    @Autowired UserProfileRepository profiles;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @MockitoBean CurrentProfileProvider currentProfileProvider;

    @Test
    void chargingIssueCreationPersistsFirstMessageAndHoldsPayout() {
        jdbc.execute("""
                INSERT INTO user_profile(keycloak_id,email,display_name)
                VALUES ('ticket-create','ticket-create@example.test','Ticket Reporter');
                INSERT INTO stations(owner_id,name,address_line,contact_phone,status)
                SELECT id,'Ticket create station','Test','000','ACTIVE'
                  FROM user_profile WHERE keycloak_id='ticket-create';
                INSERT INTO charge_points(station_id,charge_point_code,provisioning_status,operational_status)
                SELECT id,'CP-TICKET-CREATE','ACTIVE','AVAILABLE'
                  FROM stations WHERE name='Ticket create station';
                INSERT INTO connectors(charge_point_id,connector_code,connector_type,power_kw,charger_type,runtime_status)
                SELECT id,'C-TICKET-CREATE','CCS2',60,'DC','AVAILABLE'
                  FROM charge_points WHERE charge_point_code='CP-TICKET-CREATE';
                INSERT INTO bookings(driver_id,connector_id,booking_code,start_at,end_at,status,total_amount,
                    station_name_snapshot,station_address_snapshot,charge_point_code_snapshot,connector_code_snapshot,expires_at)
                SELECT u.id,c.id,'BKG-TICKET-CREATE','2026-09-29 10:00Z','2026-09-29 11:00Z','COMPLETED',
                    100000,'Ticket create station','Test','CP-TICKET-CREATE','C-TICKET-CREATE','2026-09-29 09:10Z'
                  FROM user_profile u CROSS JOIN connectors c
                 WHERE u.keycloak_id='ticket-create' AND c.connector_code='C-TICKET-CREATE';
                INSERT INTO payments(booking_id,amount,status,method,refund_amount,paid_at,provider,
                    receiving_account_ref,currency,needs_reconciliation,environment,version,payment_code)
                SELECT id,100000,'PAID','SIMULATOR',0,now(),'SIMULATOR','SIMULATOR','VND',false,'SIMULATOR',0,
                    'PAYTICKETCREATE' FROM bookings WHERE booking_code='BKG-TICKET-CREATE';
                INSERT INTO payment_transactions(payment_id,provider,receiving_account_ref,transaction_ref,
                    amount,currency,received_at,application_classification,application_reason,payment_code,version)
                SELECT id,'SIMULATOR','SIMULATOR','TICKET-CREATE-APPLIED',100000,'VND',now(),'APPLIED',NULL,payment_code,0
                  FROM payments WHERE payment_code='PAYTICKETCREATE';
                """);
        entityManager.clear();

        var reporter = profiles.findByKeycloakId("ticket-create").orElseThrow();
        UUID bookingId = bookings.findAll().stream()
                .filter(booking -> "BKG-TICKET-CREATE".equals(booking.getBookingCode()))
                .findFirst().orElseThrow().getId();
        when(currentProfileProvider.requireProfile()).thenReturn(reporter);
        assertThat(jdbc.queryForObject("SELECT is_eligible_for_payout FROM v_owner_booking_financials " +
                "WHERE booking_id = ?", Boolean.class, bookingId)).isTrue();

        var created = ticketService.create(new CreateTicketRequest(TicketCategory.CHARGING_ISSUE,
                TicketPriority.HIGH, "Connector stopped", "Charging stopped mid-session", bookingId, null));

        assertThat(created.ticketCode()).startsWith("TKT-20260930-");
        assertThat(created.messages()).hasSize(1);
        assertThat(tickets.findById(created.ticketId())).isPresent();
        assertThat(messages.findByTicketIdOrderByCreatedAtAscIdAsc(created.ticketId())).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT incident_held FROM v_owner_booking_financials " +
                "WHERE booking_id = ?", Boolean.class, bookingId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT is_eligible_for_payout FROM v_owner_booking_financials " +
                "WHERE booking_id = ?", Boolean.class, bookingId)).isFalse();
    }
}
