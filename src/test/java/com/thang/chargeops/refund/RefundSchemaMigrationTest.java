package com.thang.chargeops.refund;

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
class RefundSchemaMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine")
                    .asCompatibleSubstituteFor("postgres")
    );

    @Test
    void migrationEnforcesFullPackageSourceIdempotencyAndAttemptOwnership() throws Exception {
        Flyway baseline = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("35")
                .load();
        baseline.migrate();

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            seedTwoPaidPackages(connection);

            Flyway migration = Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .target("36")
                    .load();
            assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
            migration.validate();
            assertThat(migration.migrate().migrationsExecuted).isZero();

            UUID actorId = uuid(connection, "SELECT id FROM user_profile WHERE keycloak_id='refund-migration'");
            UUID booking1 = bookingId(connection, "RFPAY1");
            UUID payment1 = paymentId(connection, "RFPAY1");
            UUID source1 = sourceId(connection, "RFPAY1");
            UUID booking2 = bookingId(connection, "RFPAY2");
            UUID payment2 = paymentId(connection, "RFPAY2");
            UUID source2 = sourceId(connection, "RFPAY2");
            UUID basis1 = UUID.randomUUID();
            UUID basis2 = UUID.randomUUID();

            UUID refund1 = insertRefund(connection, booking1, payment1, source1, basis1, actorId);

            reject(connection, refundSql(booking1, payment1, source1, UUID.randomUUID(), actorId,
                    "120000", "VND"), "23505");
            reject(connection, refundSql(booking2, payment2, source2, basis1, actorId,
                    "120000", "VND"), "23505");
            reject(connection, refundSql(booking2, payment2, source2, UUID.randomUUID(), actorId,
                    "0", "VND"), "23514");
            reject(connection, refundSql(booking2, payment2, source2, UUID.randomUUID(), actorId,
                    "120000.50", "VND"), "23514");
            reject(connection, refundSql(booking2, payment2, source2, UUID.randomUUID(), actorId,
                    "120000", "USD"), "23514");

            UUID unappliedSource = UUID.randomUUID();
            execute(connection, """
                    INSERT INTO payment_transactions(
                        id, payment_id, provider, receiving_account_ref, transaction_ref,
                        amount, currency, received_at, application_classification,
                        application_reason, payment_code, va_number, version
                    ) VALUES (
                        '%s', '%s', 'SIMULATOR', 'SIMULATOR', 'RF-UNAPPLIED',
                        120000, 'VND', now(), 'UNAPPLIED', 'NOT_PROCESSED', 'RFPAY2', 'SIM-VA-2', 0
                    )
                    """.formatted(unappliedSource, payment2));
            reject(connection, refundSql(booking2, payment2, unappliedSource, UUID.randomUUID(), actorId,
                    "120000", "VND"), "23514");

            UUID refund2 = insertRefund(connection, booking2, payment2, source2, basis2, actorId);

            UUID request1 = UUID.randomUUID();
            UUID successAttempt = insertAttempt(connection, refund1, 1, request1, actorId,
                    "SUCCEEDED", "SIM-TRANSFER-1", null);

            reject(connection, attemptSql(refund1, 2, UUID.randomUUID(), actorId,
                    "SUCCEEDED", "SIM-TRANSFER-2", null), "23505");
            reject(connection, attemptSql(refund1, 1, UUID.randomUUID(), actorId,
                    "STARTED", null, null), "23505");
            reject(connection, attemptSql(refund1, 2, request1, actorId,
                    "STARTED", null, null), "23505");

            execute(connection, attemptSql(refund2, 1, UUID.randomUUID(), actorId,
                    "FAILED", null, "SIMULATED_DECLINE"));
            assertThat(text(connection, "SELECT status FROM refunds WHERE id='" + refund2 + "'"))
                    .isEqualTo("PENDING");

            reject(connection, """
                    UPDATE refunds
                       SET status='SUCCEEDED', successful_attempt_id='%s', completed_at=now()
                     WHERE id='%s'
                    """.formatted(successAttempt, refund2), "23503");

            execute(connection, """
                    UPDATE refunds
                       SET status='SUCCEEDED', successful_attempt_id='%s', completed_at=now()
                     WHERE id='%s'
                    """.formatted(successAttempt, refund1));
            assertThat(text(connection, "SELECT status FROM refunds WHERE id='" + refund1 + "'"))
                    .isEqualTo("SUCCEEDED");

            reject(connection, "DELETE FROM payment_transactions WHERE id='" + source1 + "'", "23503");
            reject(connection, "DELETE FROM payments WHERE id='" + payment1 + "'", "23503");
            reject(connection, "DELETE FROM bookings WHERE id='" + booking1 + "'", "23503");
        }
    }

    private static void seedTwoPaidPackages(Connection connection) throws SQLException {
        execute(connection, """
                INSERT INTO user_profile(keycloak_id,email)
                VALUES ('refund-migration','refund-migration@example.test');
                INSERT INTO stations(owner_id,name,address_line,contact_phone,status)
                SELECT id,'Refund migration station','Test','000','ACTIVE'
                FROM user_profile WHERE keycloak_id='refund-migration';
                INSERT INTO charge_points(station_id,charge_point_code,provisioning_status,operational_status)
                SELECT id,'CP-REFUND','PROVISIONED','OPERATING'
                FROM stations WHERE name='Refund migration station';
                INSERT INTO connectors(charge_point_id,connector_code,connector_type,power_kw,charger_type,runtime_status)
                SELECT id,'C-REFUND','CCS2',60,'DC','AVAILABLE'
                FROM charge_points WHERE charge_point_code='CP-REFUND';

                INSERT INTO bookings(
                    id, driver_id, connector_id, start_at, end_at, status, total_amount,
                    station_name_snapshot, station_address_snapshot,
                    charge_point_code_snapshot, connector_code_snapshot, expires_at
                )
                SELECT gen_random_uuid(), u.id, c.id,
                       ('2026-09-23 10:00Z'::timestamptz + (n || ' hours')::interval),
                       ('2026-09-23 11:00Z'::timestamptz + (n || ' hours')::interval),
                       'CONFIRMED', 120000, 'Refund migration station', 'Test', 'CP-REFUND', 'C-REFUND',
                       '2026-09-23 09:10Z'::timestamptz
                FROM user_profile u CROSS JOIN connectors c CROSS JOIN generate_series(1,2) n
                WHERE u.keycloak_id='refund-migration' AND c.connector_code='C-REFUND';

                INSERT INTO payments(
                    booking_id, amount, status, method, refund_amount, paid_at, provider,
                    receiving_account_ref, currency, needs_reconciliation, environment,
                    version, payment_code
                )
                SELECT id, 120000, 'PAID', 'SIMULATOR', 0, now(), 'SIMULATOR',
                       'SIMULATOR', 'VND', false, 'SIMULATOR', 0,
                       'RFPAY' || row_number() OVER (ORDER BY start_at)
                FROM bookings
                WHERE station_name_snapshot='Refund migration station';

                INSERT INTO payment_transactions(
                    payment_id, provider, receiving_account_ref, transaction_ref,
                    amount, currency, received_at, application_classification,
                    application_reason, payment_code, va_number, version
                )
                SELECT id, 'SIMULATOR', 'SIMULATOR', 'TX-' || payment_code,
                       amount, 'VND', now(), 'APPLIED', NULL, payment_code,
                       'SIM-VA-' || payment_code, 0
                FROM payments WHERE payment_code LIKE 'RFPAY%';
                """);
    }

    private static UUID insertRefund(
            Connection connection,
            UUID bookingId,
            UUID paymentId,
            UUID sourceId,
            UUID basisId,
            UUID actorId
    ) throws SQLException {
        execute(connection, refundSql(bookingId, paymentId, sourceId, basisId, actorId, "120000", "VND"));
        return uuid(connection, "SELECT id FROM refunds WHERE basis_id='" + basisId + "'");
    }

    private static String refundSql(
            UUID bookingId,
            UUID paymentId,
            UUID sourceId,
            UUID basisId,
            UUID actorId,
            String amount,
            String currency
    ) {
        return """
                INSERT INTO refunds(
                    booking_id, payment_id, source_payment_transaction_id, amount, currency,
                    reason, basis_type, basis_id, status, decision_at, decided_by, version
                ) VALUES (
                    '%s','%s','%s',%s,'%s','VOLUNTARY_GRACE','BOOKING_CANCELLATION',
                    '%s','PENDING',now(),'%s',0
                )
                """.formatted(bookingId, paymentId, sourceId, amount, currency, basisId, actorId);
    }

    private static UUID insertAttempt(
            Connection connection,
            UUID refundId,
            int sequence,
            UUID requestKey,
            UUID actorId,
            String status,
            String transferReference,
            String failureCode
    ) throws SQLException {
        execute(connection, attemptSql(refundId, sequence, requestKey, actorId, status,
                transferReference, failureCode));
        return uuid(connection, "SELECT id FROM refund_attempts WHERE refund_id='" + refundId
                + "' AND sequence_no=" + sequence);
    }

    private static String attemptSql(
            UUID refundId,
            int sequence,
            UUID requestKey,
            UUID actorId,
            String status,
            String transferReference,
            String failureCode
    ) {
        String completedAt = "STARTED".equals(status) ? "NULL" : "now()";
        String transfer = transferReference == null ? "NULL" : "'" + transferReference + "'";
        String failure = failureCode == null ? "NULL" : "'" + failureCode + "'";
        return """
                INSERT INTO refund_attempts(
                    refund_id, sequence_no, execution_mode, request_key, payload_hash,
                    idempotency_key, status, transfer_reference, failure_code,
                    started_at, completed_at, performed_by
                ) VALUES (
                    '%s',%d,'SIMULATOR','%s','%s','refund:%s','%s',%s,%s,
                    now(),%s,'%s'
                )
                """.formatted(refundId, sequence, requestKey, "a".repeat(64), requestKey,
                status, transfer, failure, completedAt, actorId);
    }

    private static UUID bookingId(Connection connection, String paymentCode) throws SQLException {
        return uuid(connection, "SELECT booking_id FROM payments WHERE payment_code='" + paymentCode + "'");
    }

    private static UUID paymentId(Connection connection, String paymentCode) throws SQLException {
        return uuid(connection, "SELECT id FROM payments WHERE payment_code='" + paymentCode + "'");
    }

    private static UUID sourceId(Connection connection, String paymentCode) throws SQLException {
        return uuid(connection, "SELECT id FROM payment_transactions WHERE payment_code='" + paymentCode + "'");
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

    private static String text(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static void reject(Connection connection, String sql, String sqlState) {
        assertThatThrownBy(() -> execute(connection, sql))
                .isInstanceOfSatisfying(SQLException.class,
                        exception -> assertThat(exception.getSQLState()).isEqualTo(sqlState));
    }
}
