package com.thang.chargeops.booking;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class BookingCommandHistoryMigrationTest {

    @Test
    void migratesBlankDatabaseAndCreatesCommandAndHistoryConstraints() throws Exception {
        DockerImageName image = DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                .asCompatibleSubstituteFor("postgres");
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(image)) {
            postgres.start();

            Flyway flyway = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .load();
            flyway.migrate();
            flyway.validate();

            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("33");
            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                assertThat(count(connection, """
                        SELECT count(*) FROM information_schema.tables
                        WHERE table_schema = 'public'
                          AND table_name IN ('booking_commands', 'booking_status_history')
                        """)).isEqualTo(2);
                assertThat(count(connection, """
                        SELECT count(*) FROM pg_constraint
                        WHERE conname IN (
                          'ux_booking_commands_actor_operation_key',
                          'ux_booking_status_history_booking_command',
                          'ck_booking_status_history_transition',
                          'ck_booking_status_history_actor'
                        )
                        """)).isEqualTo(4);
                assertThat(count(connection, """
                        SELECT count(*) FROM pg_trigger
                        WHERE tgname = 'trg_booking_status_history_append_only'
                          AND NOT tgisinternal
                        """)).isEqualTo(1);
            }
        }
    }

    private long count(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }
}
