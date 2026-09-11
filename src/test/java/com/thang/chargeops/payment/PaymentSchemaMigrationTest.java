package com.thang.chargeops.payment;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentSchemaMigrationTest {
    @Test
    void preservesLegacyMoneyAndEnforcesReceiptIdentityOnPostgres() throws Exception {
        try (var postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4")
                .asCompatibleSubstituteFor("postgres"))) {
            postgres.start();
            var baseline = Flyway.configure()
                    .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .target("29").load();
            baseline.migrate();
            try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
                execute(connection, """
                        INSERT INTO user_profile(keycloak_id,email) VALUES ('migration','migration@example.test');
                        INSERT INTO stations(owner_id,name,address_line,contact_phone,status)
                        SELECT id,'Migration station','Test','000','DRAFT' FROM user_profile WHERE keycloak_id='migration';
                        INSERT INTO charge_points(station_id,charge_point_code,provisioning_status,operational_status)
                        SELECT id,'CP1','DRAFT','OPERATING' FROM stations WHERE name='Migration station';
                        INSERT INTO connectors(charge_point_id,connector_code,connector_type,power_kw,charger_type,runtime_status)
                        SELECT id,'C1','CCS2',60,'DC','AVAILABLE' FROM charge_points WHERE charge_point_code='CP1';
                        INSERT INTO bookings(driver_id,connector_id,start_at,end_at,status,total_amount,
                            station_name_snapshot,station_address_snapshot,charge_point_code_snapshot,connector_code_snapshot)
                        SELECT u.id,c.id,'2026-09-11 07:00Z','2026-09-11 08:00Z','PENDING',120000,'Test','Test','CP1','C1'
                        FROM user_profile u CROSS JOIN connectors c CROSS JOIN generate_series(1,4)
                        WHERE u.keycloak_id='migration';
                        INSERT INTO payments(booking_id,amount,status,method,gateway_txn_ref,refund_amount,paid_at)
                        SELECT id,120000,
                            CASE n WHEN 1 THEN 'PAID' WHEN 2 THEN 'REFUNDED' WHEN 3 THEN 'FAILED' ELSE 'PENDING' END,
                            'VNPAY', CASE WHEN n<=2 THEN 'legacy-'||n END,
                            CASE WHEN n=2 THEN 120000 ELSE NULL END,
                            CASE WHEN n<=2 THEN '2026-09-11 06:00Z'::timestamptz END
                        FROM (SELECT id,row_number() OVER(ORDER BY id) n FROM bookings) b;
                        CREATE TEMP TABLE legacy_payments AS SELECT * FROM payments;
                        """);
                var migration = Flyway.configure()
                        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                        .target("30").load();
                migration.migrate();
                migration.validate();
                assertThat(migration.migrate().migrationsExecuted).isZero();
                assertThat(number(connection, "SELECT count(*) FROM payments")).isEqualTo(4);
                assertThat(number(connection, """
                        SELECT count(*) FROM payments p JOIN legacy_payments l USING(id)
                        WHERE (p.booking_id,p.amount,p.status,p.method,p.gateway_txn_ref,p.refund_amount,p.paid_at,
                               p.created_at,p.updated_at,p.created_by,p.updated_by)
                        IS NOT DISTINCT FROM
                              (l.booking_id,l.amount,l.status,l.method,l.gateway_txn_ref,l.refund_amount,l.paid_at,
                               l.created_at,l.updated_at,l.created_by,l.updated_by)
                        """)).isEqualTo(4);
                assertThat(number(connection, "SELECT count(*) FROM payment_transactions")).isZero();
                assertThat(number(connection, """
                        SELECT count(*) FROM payments WHERE needs_reconciliation AND currency IS NULL
                        AND receiving_account_ref IS NULL AND collected_amount IS NULL
                        AND package_refunded_amount IS NULL
                        """)).isEqualTo(4);
                assertThat(number(connection, """
                        SELECT count(*) FROM payment_migration_audit WHERE payment_count_before=4 AND payment_count_after=4
                        AND expected_amount_before=480000 AND expected_amount_after=480000
                        AND legacy_refund_amount_before=120000 AND legacy_refund_amount_after=120000
                        AND legacy_rows_requiring_reconciliation=4
                        """)).isEqualTo(1);
                execute(connection, """
                        INSERT INTO payment_transactions(payment_id,provider,receiving_account_ref,transaction_ref,amount,currency,application_classification)
                        SELECT id,'SEPAY','account-A',ref,120000,'VND','UNAPPLIED'
                        FROM payments CROSS JOIN (VALUES('TX1'),('TX2')) refs(ref) WHERE gateway_txn_ref='legacy-1';
                        """);
                assertThat(number(connection, "SELECT count(*) FROM payment_transactions WHERE payment_id IS NOT NULL")).isEqualTo(2);
                reject(connection, receipt("SEPAY", "account-A", "TX1", "120000", "VND", "UNMATCHED"), "23505");
                execute(connection, receipt("SEPAY", "account-B", "TX1", "120000", "VND", "UNMATCHED"));
                execute(connection, receipt("OTHER", "account-A", "TX1", "120000", "VND", "UNMATCHED"));
                assertThat(number(connection, "SELECT count(*) FROM payment_transactions WHERE payment_id IS NULL")).isEqualTo(2);
                reject(connection, receipt("SEPAY", "account-A", "BAD", "0", "VND", "UNMATCHED"), "23514");
                reject(connection, receipt("SEPAY", "account-A", "BAD", "-1", "VND", "UNMATCHED"), "23514");
                reject(connection, receipt("SEPAY", "account-A", "BAD", "'NaN'", "VND", "UNMATCHED"), "23514");
                reject(connection, receipt("SEPAY", "account-A", "BAD", "1", "vnd", "UNMATCHED"), "23514");
                reject(connection, receipt("SEPAY", "account-A", "BAD", "1", "VND", "APPLIED"), "23514");
                reject(connection, "UPDATE payment_transactions SET payment_id=gen_random_uuid()", "23503");
                reject(connection, "DELETE FROM payments WHERE gateway_txn_ref='legacy-1'", "23503");
                reject(connection, "UPDATE payments SET gateway_txn_ref='legacy-1' WHERE gateway_txn_ref='legacy-2'", "23505");
                reject(connection, "INSERT INTO payments(booking_id,amount,status) SELECT booking_id,1,'PENDING' FROM payments LIMIT 1", "23505");
                reject(connection, "UPDATE payments SET needs_reconciliation=false", "23514");
                reject(connection, "UPDATE payments SET collected_amount=-1", "23514");
                execute(connection, """
                        UPDATE payments SET provider='SEPAY',receiving_account_ref='account-A',currency='VND',
                        collected_amount=240000,applied_to_package_amount=120000,package_refunded_amount=0,
                        excess_amount=120000,unallocated_amount=0,needs_reconciliation=false
                        WHERE gateway_txn_ref='legacy-1'
                        """);
                reject(connection, "UPDATE payments SET package_refunded_amount=120001 WHERE gateway_txn_ref='legacy-1'", "23514");
                reject(connection, "UPDATE payments SET collected_amount=100 WHERE gateway_txn_ref='legacy-1'", "23514");
            }
        }
    }

    private static String receipt(String provider, String account, String reference, String amount, String currency, String classification) {
        return "INSERT INTO payment_transactions(provider,receiving_account_ref,transaction_ref,amount,currency,application_classification) VALUES ('%s','%s','%s',%s,'%s','%s')"
                .formatted(provider, account, reference, amount, currency, classification);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static long number(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static void reject(Connection connection, String sql, String state) {
        assertThatThrownBy(() -> execute(connection, sql)).isInstanceOfSatisfying(SQLException.class,
                exception -> assertThat(exception.getSQLState()).isEqualTo(state));
    }
}
