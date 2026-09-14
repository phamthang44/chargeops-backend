package com.thang.chargeops.payment;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;

class OrderVaMigrationTest {
    @Test void upgradePreservesMoneyAndReasonsAndEnforcesNewOrderSchema() throws Exception {
        try (var pg = new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))) {
            pg.start();
            Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).target("30").load().migrate();
            try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
                sql(c, """
                    INSERT INTO user_profile(keycloak_id,email) VALUES ('va-test','va@example.test');
                    INSERT INTO stations(owner_id,name,address_line,contact_phone,status)
                    SELECT id,'VA station','Test','000','DRAFT' FROM user_profile WHERE keycloak_id='va-test';
                    INSERT INTO charge_points(station_id,charge_point_code,provisioning_status,operational_status)
                    SELECT id,'CPVA','DRAFT','OPERATING' FROM stations WHERE name='VA station';
                    INSERT INTO connectors(charge_point_id,connector_code,connector_type,power_kw,charger_type,runtime_status)
                    SELECT id,'CVA','CCS2',60,'DC','AVAILABLE' FROM charge_points WHERE charge_point_code='CPVA';
                    INSERT INTO bookings(driver_id,connector_id,start_at,end_at,status,total_amount,
                        station_name_snapshot,station_address_snapshot,charge_point_code_snapshot,connector_code_snapshot)
                    SELECT u.id,c.id,'2026-09-12 07:00Z','2026-09-12 08:00Z','PENDING',120000,'Test','Test','CPVA','CVA'
                    FROM user_profile u CROSS JOIN connectors c WHERE u.keycloak_id='va-test';
                    INSERT INTO payments(booking_id,amount,status,method,refund_amount,provider,receiving_account_ref,currency,
                        collected_amount,applied_to_package_amount,package_refunded_amount,excess_amount,unallocated_amount,needs_reconciliation)
                    SELECT id,120000,'PAID','BANK_TRANSFER',0,'SEPAY','merchant','VND',240000,120000,0,120000,0,false FROM bookings;
                    INSERT INTO payment_transactions(payment_id,provider,receiving_account_ref,transaction_ref,amount,currency,application_classification,raw_payload)
                    SELECT p.id,'SEPAY','merchant',s,120000,'VND',s,'{"original":true}'::jsonb
                    FROM payments p CROSS JOIN (VALUES ('APPLIED'),('UNAPPLIED'),('UNMATCHED'),('LATE'),('UNDERPAID'),('OVERPAID'),('EXCESS')) v(s);
                    CREATE TEMP TABLE old_payments AS SELECT * FROM payments;
                    CREATE TEMP TABLE old_receipts AS SELECT * FROM payment_transactions;
                    """);
                var latest = Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).load();
                latest.migrate(); latest.validate();
                assertThat(latest.migrate().migrationsExecuted).isZero();
                assertThat(count(c, "SELECT count(*) FROM payments p JOIN old_payments o USING(id) WHERE (to_jsonb(p) - ARRAY['payment_code','provider_order_ref','va_number','provider_expires_at','qr_code','qr_code_url']) = to_jsonb(o)")).isEqualTo(1);
                assertThat(count(c, """
                    SELECT count(*) FROM payment_transactions t JOIN old_receipts o USING(id)
                    WHERE (to_jsonb(t) - ARRAY['application_reason','va_number','version','application_classification'])
                        = (to_jsonb(o) - 'application_classification')
                    AND t.application_classification = CASE WHEN o.application_classification='APPLIED' THEN 'APPLIED' ELSE 'UNAPPLIED' END
                    AND t.application_reason IS NOT DISTINCT FROM CASE WHEN o.application_classification='APPLIED' THEN NULL ELSE o.application_classification END
                    """)).isEqualTo(7);
                sql(c, """
                    UPDATE payments SET payment_code='CO123456',provider_order_ref='order-1',va_number='VA001',provider_expires_at=now()+interval '10 minutes';
                    """);
                reject(c, "UPDATE payments SET payment_code='bad code'", "23514");
                reject(c, "UPDATE payments SET amount=120000.50", "23514");
                reject(c, "UPDATE payments SET provider_order_ref=NULL", "23514");
                reject(c, "UPDATE payments SET refund_amount=60000", "23514");
                reject(c, "UPDATE payment_transactions SET application_classification='UNDERPAID'", "23514");
                reject(c, "UPDATE payment_transactions SET application_reason=NULL WHERE transaction_ref='LATE'", "23514");
                sql(c, "UPDATE payment_transactions SET va_number='VA001' WHERE application_classification='APPLIED'");
                reject(c, "UPDATE payment_transactions SET va_number='VA001',application_classification='APPLIED',application_reason=NULL WHERE transaction_ref='LATE'", "23505");
            }
        }
    }
    private static void sql(Connection c, String sql) throws SQLException { try (var s = c.createStatement()) { s.execute(sql); } }
    private static long count(Connection c, String sql) throws SQLException { try (var s = c.createStatement(); var r = s.executeQuery(sql)) { r.next(); return r.getLong(1); } }
    private static void reject(Connection c, String sql, String state) {
        assertThatThrownBy(() -> sql(c, sql)).isInstanceOfSatisfying(SQLException.class, ex -> assertThat(ex.getSQLState()).isEqualTo(state));
    }
}
