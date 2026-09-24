package com.thang.chargeops.payment;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class PaymentEnvironmentMigrationTest {

    @Test
    void separatesExistingSimulatorRowsFromProviderTestRows() throws Exception {
        try (var postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                .asCompatibleSubstituteFor("postgres"))) {
            postgres.start();

            Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .target("34")
                    .load()
                    .migrate();

            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                execute(connection, """
                        INSERT INTO user_profile(keycloak_id, email)
                        VALUES ('environment-migration', 'environment-migration@example.test');
                        INSERT INTO stations(owner_id, name, address_line, contact_phone, status)
                        SELECT id, 'Environment migration station', 'Test', '000', 'DRAFT'
                        FROM user_profile WHERE keycloak_id = 'environment-migration';
                        INSERT INTO charge_points(station_id, charge_point_code, provisioning_status, operational_status)
                        SELECT id, 'CP-ENV', 'DRAFT', 'OPERATING'
                        FROM stations WHERE name = 'Environment migration station';
                        INSERT INTO connectors(charge_point_id, connector_code, connector_type, power_kw, charger_type, runtime_status)
                        SELECT id, 'CON-ENV', 'CCS2', 60, 'DC', 'AVAILABLE'
                        FROM charge_points WHERE charge_point_code = 'CP-ENV';
                        INSERT INTO bookings(
                            driver_id, connector_id, start_at, end_at, status, total_amount,
                            station_name_snapshot, station_address_snapshot,
                            charge_point_code_snapshot, connector_code_snapshot, expires_at
                        )
                        SELECT u.id, c.id, '2026-09-22 10:00Z', '2026-09-22 11:00Z', 'PENDING', 120000,
                               'Environment migration station', 'Test', 'CP-ENV', 'CON-ENV',
                               '2026-09-22 09:10Z'
                        FROM user_profile u CROSS JOIN connectors c
                        WHERE u.keycloak_id = 'environment-migration' AND c.connector_code = 'CON-ENV';
                        INSERT INTO payments(
                            booking_id, amount, status, method, refund_amount,
                            provider, receiving_account_ref, currency, needs_reconciliation,
                            payment_code, environment
                        )
                        SELECT id, 120000, 'PENDING', 'SIMULATOR', 0,
                               'SIMULATOR', 'SIMULATOR', 'VND', false,
                               'COSIM001', 'TEST'
                        FROM bookings WHERE station_name_snapshot = 'Environment migration station';
                        """);

                Flyway latest = Flyway.configure()
                        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                        .load();
                latest.migrate();
                latest.validate();

                assertThat(number(connection, """
                        SELECT count(*) FROM payments
                        WHERE method = 'SIMULATOR' AND environment = 'SIMULATOR'
                        """)).isEqualTo(1);
                reject(connection, "UPDATE payments SET environment = 'UNKNOWN'", "23514");
                assertThat(text(connection, """
                        SELECT col_description('payments'::regclass, a.attnum)
                        FROM pg_attribute a
                        WHERE a.attrelid = 'payments'::regclass AND a.attname = 'environment'
                        """)).contains("Queries must select one environment");
            }
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long number(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static String text(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static void reject(Connection connection, String sql, String state) {
        assertThatThrownBy(() -> execute(connection, sql))
                .isInstanceOfSatisfying(SQLException.class,
                        exception -> assertThat(exception.getSQLState()).isEqualTo(state));
    }
}
