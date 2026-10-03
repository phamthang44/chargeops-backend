package com.thang.chargeops.station;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class StaffEquipmentActorMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                    .asCompatibleSubstituteFor("postgres")
    );

    @Test
    void staffCanAuditOperationsButCannotAuditProvisioningOrImpersonateSystem() throws Exception {
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
        flyway.validate();
        assertThat(Integer.parseInt(flyway.info().current().getVersion().getVersion()))
                .isGreaterThanOrEqualTo(48);

        UUID actorId = UUID.randomUUID();
        UUID stationId = UUID.randomUUID();
        UUID chargePointId = UUID.randomUUID();
        UUID connectorId = UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            execute(connection, """
                    INSERT INTO user_profile(id, keycloak_id, email)
                    VALUES ('%s', 'staff-actor-migration', 'staff-actor-migration@example.test')
                    """.formatted(actorId));
            execute(connection, """
                    INSERT INTO stations(id, owner_id, name, address_line, contact_phone, status)
                    VALUES ('%s', '%s', 'Staff actor migration', 'Test', '000', 'ACTIVE')
                    """.formatted(stationId, actorId));
            execute(connection, """
                    INSERT INTO charge_points(id, station_id, charge_point_code,
                                              provisioning_status, operational_status)
                    VALUES ('%s', '%s', 'CP-STAFF-AUDIT', 'ACTIVE', 'AVAILABLE')
                    """.formatted(chargePointId, stationId));
            execute(connection, """
                    INSERT INTO connectors(id, charge_point_id, connector_code, connector_type,
                                           power_kw, charger_type, runtime_status)
                    VALUES ('%s', '%s', 'C-STAFF-AUDIT', 'CCS2', 60, 'DC', 'AVAILABLE')
                    """.formatted(connectorId, chargePointId));

            execute(connection, chargePointEvent(chargePointId, "OPERATIONAL", "AVAILABLE", "OFFLINE", "STAFF", actorId));
            execute(connection, chargePointEvent(chargePointId, "PROVISIONING", "PENDING_ACTIVATION", "ACTIVE", "OWNER", actorId));
            reject(connection, chargePointEvent(chargePointId, "PROVISIONING", "PENDING_ACTIVATION", "ACTIVE", "STAFF", actorId), "23514");
            reject(connection, chargePointEvent(chargePointId, "OPERATIONAL", "OFFLINE", "AVAILABLE", "SYSTEM", actorId), "23514");

            execute(connection, connectorEvent(connectorId, "AVAILABLE", "OFFLINE", "STAFF", actorId));
            execute(connection, connectorEvent(connectorId, "AVAILABLE", "IN_USE", "SYSTEM", null));
            reject(connection, connectorEvent(connectorId, "OFFLINE", "AVAILABLE", "STAFF", null), "23514");
            reject(connection, connectorEvent(connectorId, "IN_USE", "AVAILABLE", "SYSTEM", actorId), "23514");
        }
    }

    private static String chargePointEvent(UUID chargePointId, String dimension, String from,
                                          String to, String actor, UUID performedBy) {
        return """
                INSERT INTO charge_point_status_events(charge_point_id, status_dimension,
                                                       from_status, to_status, actor_type, performed_by)
                VALUES ('%s', '%s', '%s', '%s', '%s', '%s')
                """.formatted(chargePointId, dimension, from, to, actor, performedBy);
    }

    private static String connectorEvent(UUID connectorId, String from, String to,
                                         String actor, UUID performedBy) {
        String actorId = performedBy == null ? "NULL" : "'" + performedBy + "'";
        return """
                INSERT INTO connector_status_events(connector_id, from_status, to_status,
                                                    actor_type, performed_by)
                VALUES ('%s', '%s', '%s', '%s', %s)
                """.formatted(connectorId, from, to, actor, actorId);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void reject(Connection connection, String sql, String sqlState) {
        assertThatThrownBy(() -> execute(connection, sql))
                .isInstanceOfSatisfying(SQLException.class,
                        exception -> assertThat(exception.getSQLState()).isEqualTo(sqlState));
    }
}
