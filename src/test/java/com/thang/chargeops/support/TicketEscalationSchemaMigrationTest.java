package com.thang.chargeops.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThatCode;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.docker.compose.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TicketEscalationSchemaMigrationTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired JdbcTemplate jdbc;

    @Test
    void escalationReviewEventsAndAdminCloseReasonSatisfyDatabaseConstraints() {
        jdbc.execute("""
            INSERT INTO user_profile(keycloak_id,email,display_name,status)
            VALUES ('schema-admin','schema-admin@example.test','Admin','ACTIVE'),
                   ('schema-owner','schema-owner@example.test','Owner','ACTIVE'),
                   ('schema-reporter','schema-reporter@example.test','Reporter','ACTIVE');
            INSERT INTO stations(owner_id,name,address_line,contact_phone,status)
            SELECT id,'Escalation schema station','Test','000','ACTIVE'
            FROM user_profile WHERE keycloak_id='schema-owner';
            INSERT INTO support_tickets(ticket_code,category,status,priority,reporter_id,station_id,
                                        subject,description,close_reason)
            SELECT 'TKT-20261003-9901','CHARGING_ISSUE','CLOSED','MEDIUM',r.id,s.id,
                   'Schema test','Escalation close constraint','ADMIN_SUPPORT_CASE_CLOSED'
            FROM user_profile r CROSS JOIN stations s
            WHERE r.keycloak_id='schema-reporter' AND s.name='Escalation schema station';
            """);

        assertThatCode(() -> jdbc.execute("""
            INSERT INTO ticket_events(ticket_id,actor_id,actor_kind,event_type,from_status,to_status,
                                      resolution_cycle,reason,created_at)
            SELECT t.id,a.id,'ADMIN','CLOSE_SUPPORT_CASE','IN_PROGRESS','CLOSED',0,
                   'No platform action remains',now()
            FROM support_tickets t CROSS JOIN user_profile a
            WHERE t.ticket_code='TKT-20261003-9901' AND a.keycloak_id='schema-admin';
            INSERT INTO ticket_events(ticket_id,actor_id,actor_kind,event_type,from_status,to_status,
                                      resolution_cycle,reason,created_at)
            SELECT t.id,a.id,'ADMIN','RETURN_TO_STATION','IN_PROGRESS','IN_PROGRESS',0,
                   'Station should continue handling',now()
            FROM support_tickets t CROSS JOIN user_profile a
            WHERE t.ticket_code='TKT-20261003-9901' AND a.keycloak_id='schema-admin';
            """)).doesNotThrowAnyException();
    }
}
