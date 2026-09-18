package com.thang.chargeops.booking;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
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
class BookingSchemaMigrationTest {

    @Test
    void migratesBlankDatabaseThroughV23ThenExpandsBookingSchemaToV28() throws Exception {
        DockerImageName image = DockerImageName.parse("postgis/postgis:16-3.4")
                .asCompatibleSubstituteFor("postgres");
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(image)) {
            postgres.start();

            Flyway throughV23 = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("23"))
                    .load();
            throughV23.migrate();
            assertThat(throughV23.info().current().getVersion().getVersion()).isEqualTo("23");

            Flyway latest = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration")
                    .target(MigrationVersion.fromVersion("30"))
                    .load();
            latest.migrate();
            latest.validate();
            assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("30");

            try (Connection connection = DriverManager.getConnection(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                assertThat(count(connection, """
                        SELECT count(*) FROM payment_migration_audit
                        WHERE migration_version = '30' AND payment_count_before = 0
                          AND payment_count_after = 0 AND expected_amount_before = 0
                          AND expected_amount_after = 0 AND legacy_rows_requiring_reconciliation = 0
                        """)).isEqualTo(1);
                assertThat(count(connection, """
                        SELECT count(*)
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'bookings'
                          AND column_name IN (
                            'booking_code', 'version', 'policy_version', 'policy_snapshot',
                            'payment_confirmed_at', 'free_cancellation_deadline',
                            'check_in_deadline', 'cancellation_reason',
                            'charging_started_at', 'completed_at'
                          )
                        """)).isEqualTo(10);
                assertThat(count(connection, """
                        SELECT count(*) FROM information_schema.tables
                        WHERE table_schema = 'public' AND table_name = 'booking_price_lines'
                        """)).isEqualTo(1);
                assertThat(count(connection, """
                        SELECT count(*) FROM pg_constraint
                        WHERE conname IN (
                          'ex_bookings_no_overlap',
                          'ux_booking_price_lines_booking_sequence',
                          'ck_booking_price_lines_time_range',
                          'ck_booking_price_lines_duration',
                          'ck_booking_price_lines_rate',
                          'ck_booking_price_lines_energy',
                          'ck_booking_price_lines_power',
                          'ck_booking_price_lines_factor',
                          'ck_booking_price_lines_amount'
                        )
                        """)).isEqualTo(9);
                assertThat(count(connection, """
                        SELECT count(*) FROM pg_indexes
                        WHERE schemaname = 'public'
                          AND indexname IN (
                            'ux_bookings_booking_code',
                            'idx_bookings_driver_start_status',
                            'idx_booking_price_lines_booking'
                          )
                        """)).isEqualTo(3);
                assertThat(count(connection, """
                        SELECT count(*)
                        FROM pg_constraint
                        WHERE contype = 'f'
                          AND conrelid = 'booking_price_lines'::regclass
                          AND confrelid = 'bookings'::regclass
                          AND confdeltype = 'r'
                        """)).isEqualTo(1);
            }
        }
    }

    private long count(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }
}
