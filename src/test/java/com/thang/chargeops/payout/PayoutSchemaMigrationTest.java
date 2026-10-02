package com.thang.chargeops.payout;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class PayoutSchemaMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine").asCompatibleSubstituteFor("postgres"));

    @Test
    void migratesWithoutChangingMoneyAndKeepsPayoutAndProjectionConsistent() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("40").load().migrate();
        try (Connection db = connect()) {
            seedPaidBooking(db);
            BigDecimal paymentBefore = decimal(db, "SELECT sum(amount) FROM payments");
            BigDecimal receiptBefore = decimal(db, "SELECT sum(amount) FROM payment_transactions");
            BigDecimal refundBefore = decimal(db, "SELECT coalesce(sum(amount),0) FROM refunds");

            Flyway flyway = Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load();
            assertThat(flyway.migrate().migrationsExecuted).isGreaterThanOrEqualTo(1);
            flyway.validate();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            assertThat(decimal(db, "SELECT sum(amount) FROM payments")).isEqualByComparingTo(paymentBefore);
            assertThat(decimal(db, "SELECT sum(amount) FROM payment_transactions")).isEqualByComparingTo(receiptBefore);
            assertThat(decimal(db, "SELECT coalesce(sum(amount),0) FROM refunds")).isEqualByComparingTo(refundBefore);

            UUID owner = uuid(db, "SELECT id FROM user_profile WHERE keycloak_id='payout-schema'");
            UUID booking = uuid(db, "SELECT id FROM bookings WHERE booking_code='BKG-PAYOUT-SCHEMA'");
            UUID payment = uuid(db, "SELECT id FROM payments WHERE payment_code='PAYPAYOUTSCHEMA'");
            UUID receipt = uuid(db, "SELECT id FROM payment_transactions WHERE transaction_ref='PAYOUT-APPLIED'");
            exec(db, "INSERT INTO user_profile(keycloak_id,email) VALUES ('payout-new-owner','new-owner@example.test')");
            exec(db, "UPDATE stations SET owner_id=(SELECT id FROM user_profile WHERE keycloak_id='payout-new-owner') " +
                    "WHERE name='Payout schema station'");
            assertThat(uuid(db, "SELECT owner_id FROM v_owner_booking_financials")).isEqualTo(owner);
            assertThat(decimal(db, "SELECT gross_collected_amount FROM v_owner_booking_financials")).isEqualByComparingTo("120000");
            assertThat(decimal(db, "SELECT applied_amount FROM v_owner_booking_financials")).isEqualByComparingTo("100000");
            assertThat(bool(db, "SELECT is_eligible_for_payout FROM v_owner_booking_financials")).isTrue();

            UUID ticket = UUID.randomUUID();
            exec(db, "INSERT INTO support_tickets(id,ticket_code,category,status,priority,reporter_id,booking_id,station_id,subject,description) " +
                    "SELECT '" + ticket + "','TKT-20260930-0001','CHARGING_ISSUE','RESOLVED','MEDIUM','" + owner +
                    "','" + booking + "',id,'Issue','Investigating' FROM stations WHERE name='Payout schema station'");
            assertThat(bool(db, "SELECT incident_held FROM v_owner_booking_financials")).isTrue();
            assertThat(bool(db, "SELECT is_eligible_for_payout FROM v_owner_booking_financials")).isFalse();
            exec(db, "INSERT INTO ticket_findings(ticket_id,booking_id,conclusion,affected_at,reason,recorded_at,recorded_by) " +
                    "VALUES ('" + ticket + "','" + booking + "','NOT_STATION_FAILURE','2026-09-29 10:00Z'," +
                    "'Inspected','2026-09-29 11:00Z','" + owner + "')");
            assertThat(bool(db, "SELECT incident_held FROM v_owner_booking_financials")).isFalse();

            exec(db, "INSERT INTO ticket_events(ticket_id,actor_id,actor_kind,event_type,from_status,to_status," +
                    "resolution_cycle,reason,created_at) VALUES ('" + ticket + "','" + owner +
                    "','REPORTER','REPORTER_CONTINUED','RESOLVED','IN_PROGRESS',0," +
                    "'Driver disputes the finding','2026-09-29 12:00Z')");
            assertThat(bool(db, "SELECT incident_held FROM v_owner_booking_financials")).isTrue();
            exec(db, "INSERT INTO ticket_findings(ticket_id,booking_id,conclusion,affected_at,reason,recorded_at,recorded_by) " +
                    "VALUES ('" + ticket + "','" + booking + "','NOT_STATION_FAILURE','2026-09-29 10:00Z'," +
                    "'Admin reviewed the dispute','2026-09-29 13:00Z','" + owner + "')");
            assertThat(bool(db, "SELECT incident_held FROM v_owner_booking_financials")).isFalse();

            UUID secondTicket = UUID.randomUUID();
            exec(db, "INSERT INTO support_tickets(id,ticket_code,category,status,priority,reporter_id,booking_id,station_id,subject,description) " +
                    "SELECT '" + secondTicket + "','TKT-20260930-0002','CHARGING_ISSUE','OPEN','MEDIUM','" + owner +
                    "','" + booking + "',id,'Second issue','Independent incident' FROM stations WHERE name='Payout schema station'");
            assertThat(bool(db, "SELECT incident_held FROM v_owner_booking_financials")).isTrue();
            exec(db, "INSERT INTO ticket_findings(ticket_id,booking_id,conclusion,affected_at,reason,recorded_at,recorded_by) " +
                    "VALUES ('" + secondTicket + "','" + booking + "','NOT_STATION_FAILURE','2026-09-29 10:00Z'," +
                    "'Independent incident ruled out','2026-09-29 14:00Z','" + owner + "')");
            assertThat(bool(db, "SELECT incident_held FROM v_owner_booking_financials")).isFalse();

            UUID payout = UUID.randomUUID();
            UUID item = UUID.randomUUID();
            db.setAutoCommit(false);
            exec(db, payoutInsert(payout, owner, "PO-20260930-0001"));
            exec(db, itemInsert(item, payout, booking, payment));
            db.commit();
            db.setAutoCommit(true);
            assertThat(decimal(db, "SELECT payout_pending_amount FROM v_owner_booking_financials")).isEqualByComparingTo("100000");
            assertThat(bool(db, "SELECT is_eligible_for_payout FROM v_owner_booking_financials")).isFalse();

            UUID duplicate = UUID.randomUUID();
            db.setAutoCommit(false);
            exec(db, payoutInsert(duplicate, owner, "PO-20260930-0002"));
            assertThatThrownBy(() -> exec(db, itemInsert(UUID.randomUUID(), duplicate, booking, payment)))
                    .isInstanceOf(SQLException.class);
            db.rollback();
            db.setAutoCommit(true);

            UUID failedAttempt = UUID.randomUUID();
            exec(db, attemptInsert(failedAttempt, payout, owner, 1));
            exec(db, "UPDATE payout_attempts SET status='FAILED', failure_code='SIM_FAIL'," +
                    " performed_at=started_at,completed_at=started_at WHERE id='" + failedAttempt + "'");
            assertThat(string(db, "SELECT status FROM owner_payouts WHERE id='" + payout + "'")).isEqualTo("PENDING");
            assertThat(string(db, "SELECT status FROM owner_payout_items WHERE id='" + item + "'")).isEqualTo("PENDING");

            UUID success = UUID.randomUUID();
            db.setAutoCommit(false);
            exec(db, attemptInsert(success, payout, owner, 2));
            exec(db, "UPDATE payout_attempts SET status='SUCCEEDED', transfer_reference='SIM-TRANSFER-1'," +
                    " performed_at=started_at,completed_at=started_at WHERE id='" + success + "'");
            exec(db, "UPDATE owner_payouts SET status='SUCCEEDED', successful_attempt_id='" + success +
                    "',completed_at='2026-09-30 12:00Z' WHERE id='" + payout + "'");
            exec(db, "UPDATE owner_payout_items SET status='SUCCEEDED' WHERE id='" + item + "'");
            db.commit();
            db.setAutoCommit(true);
            assertThat(decimal(db, "SELECT paid_to_owner_amount FROM v_owner_booking_financials")).isEqualByComparingTo("100000");
            assertThatThrownBy(() -> exec(db, "UPDATE owner_payout_items SET status='RELEASED' WHERE id='" + item + "'"))
                    .isInstanceOf(SQLException.class);

            UUID refund = UUID.randomUUID();
            exec(db, "INSERT INTO refunds(id,booking_id,payment_id,source_payment_transaction_id,amount,currency," +
                    "reason,basis_type,basis_id,status,decision_at,decided_by,execution_policy,requires_admin_action) " +
                    "VALUES ('" + refund + "','" + booking + "','" + payment + "','" + receipt +
                    "',100000,'VND','STATION_FAILURE','STATION_FAILURE_FINDING','" + UUID.randomUUID() +
                    "','PENDING','2026-09-30 13:00Z','" + owner + "','ADMIN_REQUIRED',true)");
            UUID adjustment = UUID.randomUUID();
            exec(db, "INSERT INTO owner_adjustments(id,owner_id,booking_id,refund_id,source_payout_item_id,amount," +
                    "currency,environment,reason,recorded_at,recorded_by) VALUES ('" + adjustment + "','" + owner +
                    "','" + booking + "','" + refund + "','" + item +
                    "',-100000,'VND','SIMULATOR','REFUND_AFTER_PAYOUT','2026-09-30 13:01Z','" + owner + "')");
            assertThat(decimal(db, "SELECT adjustment_due_amount FROM v_owner_booking_financials"))
                    .isEqualByComparingTo("100000");
            assertThatThrownBy(() -> exec(db, "UPDATE owner_adjustments SET amount=-1 WHERE id='" + adjustment + "'"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> exec(db, "INSERT INTO owner_adjustments(owner_id,booking_id,refund_id," +
                    "source_payout_item_id,amount,currency,environment,reason,recorded_at,recorded_by) VALUES ('" +
                    owner + "','" + booking + "','" + refund + "','" + item +
                    "',-100000,'VND','SIMULATOR','REFUND_AFTER_PAYOUT',now(),'" + owner + "')"))
                    .isInstanceOf(SQLException.class);

            exec(db, "INSERT INTO bookings(driver_id,connector_id,booking_code,start_at,end_at,status," +
                    "cancellation_reason,total_amount,station_name_snapshot,station_address_snapshot," +
                    "charge_point_code_snapshot,connector_code_snapshot,expires_at) SELECT driver_id,connector_id," +
                    "'BKG-PAYOUT-NOSHOW',start_at,end_at,'CANCELLED','NO_SHOW',total_amount,station_name_snapshot," +
                    "station_address_snapshot,charge_point_code_snapshot,connector_code_snapshot,expires_at " +
                    "FROM bookings WHERE id='" + booking + "'");
            exec(db, "INSERT INTO payments(booking_id,amount,status,method,refund_amount,paid_at,provider," +
                    "receiving_account_ref,currency,needs_reconciliation,environment,version,payment_code) " +
                    "SELECT id,100000,'PAID','SIMULATOR',0,now(),'SIMULATOR','SIMULATOR','VND',false," +
                    "'SIMULATOR',0,'PAYNOSHOW' FROM bookings WHERE booking_code='BKG-PAYOUT-NOSHOW'");
            exec(db, "INSERT INTO payment_transactions(payment_id,provider,receiving_account_ref,transaction_ref," +
                    "amount,currency,received_at,application_classification,application_reason,payment_code,version) " +
                    "SELECT id,'SIMULATOR','SIMULATOR','PAYOUT-NOSHOW',100000,'VND',now(),'APPLIED',NULL,payment_code,0 " +
                    "FROM payments WHERE payment_code='PAYNOSHOW'");
            assertThat(bool(db, "SELECT is_eligible_for_payout FROM v_owner_booking_financials " +
                    "WHERE booking_code='BKG-PAYOUT-NOSHOW'")).isTrue();
            exec(db, "UPDATE bookings SET cancellation_reason='DRIVER_CANCELLED' " +
                    "WHERE booking_code='BKG-PAYOUT-NOSHOW'");
            assertThat(bool(db, "SELECT is_eligible_for_payout FROM v_owner_booking_financials " +
                    "WHERE booking_code='BKG-PAYOUT-NOSHOW'")).isTrue();
            exec(db, "UPDATE payments SET environment='LEGACY' WHERE payment_code='PAYNOSHOW'");
            assertThat(decimal(db, "SELECT count(*) FROM v_owner_booking_financials " +
                    "WHERE booking_code='BKG-PAYOUT-NOSHOW'")).isEqualByComparingTo("0");

            String definition = string(db, "SELECT pg_get_viewdef('v_owner_booking_financials'::regclass, true)");
            BigDecimal dueBeforeRebuild = decimal(db, "SELECT adjustment_due_amount FROM v_owner_booking_financials " +
                    "WHERE booking_code='BKG-PAYOUT-SCHEMA'");
            exec(db, "DROP VIEW v_owner_booking_financials");
            exec(db, "CREATE VIEW v_owner_booking_financials AS " + definition);
            assertThat(decimal(db, "SELECT adjustment_due_amount FROM v_owner_booking_financials " +
                    "WHERE booking_code='BKG-PAYOUT-SCHEMA'")).isEqualByComparingTo(dueBeforeRebuild);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void seedPaidBooking(Connection db) throws SQLException {
        exec(db, """
                INSERT INTO user_profile(keycloak_id,email) VALUES ('payout-schema','payout-schema@example.test');
                INSERT INTO stations(owner_id,name,address_line,contact_phone,status)
                SELECT id,'Payout schema station','Test','000','ACTIVE' FROM user_profile WHERE keycloak_id='payout-schema';
                INSERT INTO charge_points(station_id,charge_point_code,provisioning_status,operational_status)
                SELECT id,'CP-PAYOUT-SCHEMA','PROVISIONED','OPERATING' FROM stations WHERE name='Payout schema station';
                INSERT INTO connectors(charge_point_id,connector_code,connector_type,power_kw,charger_type,runtime_status)
                SELECT id,'C-PAYOUT-SCHEMA','CCS2',60,'DC','AVAILABLE' FROM charge_points WHERE charge_point_code='CP-PAYOUT-SCHEMA';
                INSERT INTO bookings(driver_id,connector_id,booking_code,start_at,end_at,status,total_amount,
                    station_name_snapshot,station_address_snapshot,charge_point_code_snapshot,connector_code_snapshot,expires_at)
                SELECT u.id,c.id,'BKG-PAYOUT-SCHEMA','2026-09-29 10:00Z','2026-09-29 11:00Z','COMPLETED',
                    100000,'Payout schema station','Test','CP-PAYOUT-SCHEMA','C-PAYOUT-SCHEMA','2026-09-29 09:10Z'
                FROM user_profile u CROSS JOIN connectors c
                WHERE u.keycloak_id='payout-schema' AND c.connector_code='C-PAYOUT-SCHEMA';
                INSERT INTO payments(booking_id,amount,status,method,refund_amount,paid_at,provider,
                    receiving_account_ref,currency,needs_reconciliation,environment,version,payment_code)
                SELECT id,100000,'PAID','SIMULATOR',0,now(),'SIMULATOR','SIMULATOR','VND',false,'SIMULATOR',0,
                    'PAYPAYOUTSCHEMA' FROM bookings WHERE booking_code='BKG-PAYOUT-SCHEMA';
                INSERT INTO payment_transactions(payment_id,provider,receiving_account_ref,transaction_ref,
                    amount,currency,received_at,application_classification,application_reason,payment_code,version)
                SELECT id,'SIMULATOR','SIMULATOR','PAYOUT-APPLIED',100000,'VND',now(),'APPLIED',NULL,payment_code,0
                FROM payments WHERE payment_code='PAYPAYOUTSCHEMA';
                INSERT INTO payment_transactions(payment_id,provider,receiving_account_ref,transaction_ref,
                    amount,currency,received_at,application_classification,application_reason,payment_code,version)
                SELECT id,'SIMULATOR','SIMULATOR','PAYOUT-UNAPPLIED',20000,'VND',now(),'UNAPPLIED','EXTRA',payment_code,0
                FROM payments WHERE payment_code='PAYPAYOUTSCHEMA';
                """);
    }

    private String payoutInsert(UUID payout, UUID owner, String code) {
        return "INSERT INTO owner_payouts(id,payout_code,owner_id,amount,currency,environment,status,period_start,period_end) " +
                "VALUES ('" + payout + "','" + code + "','" + owner +
                "',100000,'VND','SIMULATOR','PENDING','2026-09-01 00:00Z','2026-10-01 00:00Z')";
    }

    private String itemInsert(UUID item, UUID payout, UUID booking, UUID payment) {
        return "INSERT INTO owner_payout_items(id,payout_id,booking_id,payment_id,amount,currency,environment,status) " +
                "VALUES ('" + item + "','" + payout + "','" + booking + "','" + payment +
                "',100000,'VND','SIMULATOR','PENDING')";
    }

    private String attemptInsert(UUID attempt, UUID payout, UUID owner, int sequence) {
        return "INSERT INTO payout_attempts(id,payout_id,sequence_no,execution_mode,request_key,payload_hash," +
                "idempotency_key,amount,currency,environment,status,started_at,performed_by) VALUES ('" + attempt +
                "','" + payout + "'," + sequence + ",'SIMULATOR','" + UUID.randomUUID() + "','" +
                "a".repeat(64) + "','payout:" + attempt +
                "',100000,'VND','SIMULATOR','STARTED','2026-09-30 12:00Z','" + owner + "')";
    }

    private void exec(Connection db, String sql) throws SQLException {
        try (Statement statement = db.createStatement()) { statement.execute(sql); }
    }

    private UUID uuid(Connection db, String sql) throws SQLException {
        try (Statement statement = db.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getObject(1, UUID.class);
        }
    }

    private BigDecimal decimal(Connection db, String sql) throws SQLException {
        try (Statement statement = db.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getBigDecimal(1);
        }
    }

    private boolean bool(Connection db, String sql) throws SQLException {
        try (Statement statement = db.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getBoolean(1);
        }
    }

    private String string(Connection db, String sql) throws SQLException {
        try (Statement statement = db.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }
}
