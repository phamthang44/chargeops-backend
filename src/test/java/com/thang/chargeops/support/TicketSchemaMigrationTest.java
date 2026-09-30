package com.thang.chargeops.support;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class TicketSchemaMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                    .asCompatibleSubstituteFor("postgres")
    );

    @Test
    void migrationValidatesUniqueCodeAppendOnlyAuditAndRestrictiveHistory() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("40");

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            seedTicketContext(connection);
            UUID actorId = uuid(connection, "SELECT id FROM user_profile WHERE keycloak_id='ticket-migration'");
            UUID stationId = uuid(connection, "SELECT id FROM stations WHERE name='Ticket migration station'");
            UUID bookingId = uuid(connection, "SELECT id FROM bookings WHERE station_name_snapshot='Ticket migration station'");

            UUID ticketId = UUID.randomUUID();
            execute(connection, ticketSql(ticketId, "TKT-20260929-0001", actorId, stationId, bookingId));
            reject(connection, ticketSql(UUID.randomUUID(), "TKT-20260929-0001", actorId, stationId, bookingId), "23505");

            UUID messageId = UUID.randomUUID();
            execute(connection, """
                    INSERT INTO ticket_messages(id,ticket_id,author_id,author_kind,body,created_at)
                    VALUES ('%s','%s','%s','REPORTER','Initial report',now())
                    """.formatted(messageId, ticketId, actorId));
            reject(connection, "UPDATE ticket_messages SET body='rewritten' WHERE id='" + messageId + "'", "55000");
            reject(connection, "DELETE FROM ticket_messages WHERE id='" + messageId + "'", "55000");

            UUID findingId = UUID.randomUUID();
            execute(connection, """
                    INSERT INTO ticket_findings(
                        id,ticket_id,booking_id,conclusion,affected_at,reason,recorded_at,recorded_by
                    ) VALUES (
                        '%s','%s','%s','STATION_FAILURE',now() - interval '1 minute',
                        'Connector fault confirmed',now(),'%s'
                    )
                    """.formatted(findingId, ticketId, bookingId, actorId));
            reject(connection, "UPDATE ticket_findings SET reason='rewritten' WHERE id='" + findingId + "'", "55000");
            reject(connection, "DELETE FROM ticket_findings WHERE id='" + findingId + "'", "55000");

            reject(connection, "DELETE FROM bookings WHERE id='" + bookingId + "'", "23503");
            reject(connection, "DELETE FROM support_tickets WHERE id='" + ticketId + "'", "23503");
            assertThat(count(connection, "SELECT count(*) FROM ticket_messages WHERE ticket_id='" + ticketId + "'"))
                    .isEqualTo(1);
            assertThat(count(connection, "SELECT count(*) FROM ticket_findings WHERE ticket_id='" + ticketId + "'"))
                    .isEqualTo(1);
        }
    }

    private static void seedTicketContext(Connection connection) throws SQLException {
        execute(connection, """
                INSERT INTO user_profile(keycloak_id,email)
                VALUES ('ticket-migration','ticket-migration@example.test');
                INSERT INTO stations(owner_id,name,address_line,contact_phone,status)
                SELECT id,'Ticket migration station','Test','000','ACTIVE'
                FROM user_profile WHERE keycloak_id='ticket-migration';
                INSERT INTO charge_points(station_id,charge_point_code,provisioning_status,operational_status)
                SELECT id,'CP-TICKET','PROVISIONED','OPERATING'
                FROM stations WHERE name='Ticket migration station';
                INSERT INTO connectors(charge_point_id,connector_code,connector_type,power_kw,charger_type,runtime_status)
                SELECT id,'C-TICKET','CCS2',60,'DC','AVAILABLE'
                FROM charge_points WHERE charge_point_code='CP-TICKET';
                INSERT INTO bookings(
                    driver_id,connector_id,start_at,end_at,status,total_amount,
                    station_name_snapshot,station_address_snapshot,
                    charge_point_code_snapshot,connector_code_snapshot,expires_at
                )
                SELECT u.id,c.id,'2026-09-29 08:00Z','2026-09-29 09:00Z','CONFIRMED',120000,
                       'Ticket migration station','Test','CP-TICKET','C-TICKET','2026-09-29 07:10Z'
                FROM user_profile u CROSS JOIN connectors c
                WHERE u.keycloak_id='ticket-migration' AND c.connector_code='C-TICKET';
                """);
    }

    private static String ticketSql(
            UUID id, String code, UUID actorId, UUID stationId, UUID bookingId
    ) {
        return """
                INSERT INTO support_tickets(
                    id,ticket_code,category,status,priority,reporter_id,station_id,booking_id,
                    subject,description,version
                ) VALUES (
                    '%s','%s','CHARGING_ISSUE','OPEN','HIGH','%s','%s','%s',
                    'Connector stopped','Connector stopped during the paid session',0
                )
                """.formatted(id, code, actorId, stationId, bookingId);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static UUID uuid(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getObject(1, UUID.class);
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static void reject(Connection connection, String sql, String sqlState) {
        assertThatThrownBy(() -> execute(connection, sql))
                .isInstanceOfSatisfying(SQLException.class,
                        exception -> assertThat(exception.getSQLState()).isEqualTo(sqlState));
    }
}
