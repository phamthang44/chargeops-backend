package com.thang.chargeops.support;

import com.thang.chargeops.booking.entity.Booking;
import com.thang.chargeops.booking.repository.BookingRepository;
import com.thang.chargeops.profile.entity.UserProfile;
import com.thang.chargeops.profile.repository.UserProfileRepository;
import com.thang.chargeops.station.entity.Station;
import com.thang.chargeops.station.repository.StationRepository;
import com.thang.chargeops.support.entity.SupportTicket;
import com.thang.chargeops.support.entity.TicketFinding;
import com.thang.chargeops.support.entity.TicketMessage;
import com.thang.chargeops.support.model.*;
import com.thang.chargeops.support.repository.SupportTicketRepository;
import com.thang.chargeops.support.repository.TicketFindingRepository;
import com.thang.chargeops.support.repository.TicketMessageRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
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
class TicketJpaMappingTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                    .asCompatibleSubstituteFor("postgres")
    );

    @Autowired SupportTicketRepository ticketRepository;
    @Autowired TicketMessageRepository messageRepository;
    @Autowired TicketFindingRepository findingRepository;
    @Autowired BookingRepository bookingRepository;
    @Autowired StationRepository stationRepository;
    @Autowired UserProfileRepository profileRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;

    @BeforeEach
    void seedTicketContext() {
        jdbcTemplate.execute("""
                INSERT INTO user_profile(keycloak_id,email)
                VALUES ('ticket-jpa','ticket-jpa@example.test');
                INSERT INTO stations(owner_id,name,address_line,contact_phone,status)
                SELECT id,'Ticket JPA station','Test','000','ACTIVE'
                FROM user_profile WHERE keycloak_id='ticket-jpa';
                INSERT INTO charge_points(station_id,charge_point_code,provisioning_status,operational_status)
                SELECT id,'CP-TICKET-JPA','PROVISIONED','OPERATING'
                FROM stations WHERE name='Ticket JPA station';
                INSERT INTO connectors(charge_point_id,connector_code,connector_type,power_kw,charger_type,runtime_status)
                SELECT id,'C-TICKET-JPA','CCS2',60,'DC','AVAILABLE'
                FROM charge_points WHERE charge_point_code='CP-TICKET-JPA';
                INSERT INTO bookings(
                    driver_id,connector_id,start_at,end_at,status,total_amount,
                    station_name_snapshot,station_address_snapshot,
                    charge_point_code_snapshot,connector_code_snapshot,expires_at
                )
                SELECT u.id,c.id,'2026-09-29 08:00Z','2026-09-29 09:00Z','CONFIRMED',120000,
                       'Ticket JPA station','Test','CP-TICKET-JPA','C-TICKET-JPA','2026-09-29 07:10Z'
                FROM user_profile u CROSS JOIN connectors c
                WHERE u.keycloak_id='ticket-jpa' AND c.connector_code='C-TICKET-JPA';
                """);
        entityManager.clear();
    }

    @Test
    void hibernateValidateAndRepositoriesRoundTripTicketMessagesAndFindings() {
        UserProfile actor = profileRepository.findByKeycloakId("ticket-jpa").orElseThrow();
        Station station = stationRepository.findAll().stream()
                .filter(candidate -> "Ticket JPA station".equals(candidate.getName()))
                .findFirst().orElseThrow();
        Booking booking = bookingRepository.findAll().stream()
                .filter(candidate -> "Ticket JPA station".equals(candidate.getStationNameSnapshot()))
                .findFirst().orElseThrow();

        SupportTicket ticket = ticketRepository.saveAndFlush(SupportTicket.open(
                "TKT-20260929-0099",
                TicketCategory.CHARGING_ISSUE,
                TicketPriority.HIGH,
                actor,
                station,
                booking,
                new SupportTicket.TicketDetails("Connector stopped",
                        "Connector stopped during the paid session")
        ));
        Instant affectedAt = Instant.parse("2026-09-29T08:20:00Z");
        TicketMessage message = messageRepository.saveAndFlush(TicketMessage.create(
                ticket, actor, TicketActorKind.REPORTER, "Initial report", affectedAt
        ));
        TicketFinding finding = findingRepository.saveAndFlush(TicketFinding.record(
                ticket,
                booking,
                TicketFindingConclusion.STATION_FAILURE,
                affectedAt,
                "Connector fault confirmed",
                affectedAt.plusSeconds(60),
                actor
        ));

        UUID ticketId = ticket.getId();
        entityManager.clear();

        assertThat(ticketRepository.findByTicketCode("TKT-20260929-0099"))
                .get().extracting(SupportTicket::getId).isEqualTo(ticketId);
        assertThat(messageRepository.findByTicketIdOrderByCreatedAtAscIdAsc(ticketId))
                .extracting(TicketMessage::getId).containsExactly(message.getId());
        assertThat(findingRepository.findByTicketIdOrderByRecordedAtAscIdAsc(ticketId))
                .extracting(TicketFinding::getId).containsExactly(finding.getId());
    }
}
